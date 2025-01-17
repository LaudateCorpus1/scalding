/*
Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.twitter.scalding

import cascading.flow.{Flow, FlowDef}
import com.stripe.dagon.{Dag, Id, Rule}
import com.twitter.algebird.monad.Trampoline
import com.twitter.algebird.{Monad, Monoid, Semigroup}
import com.twitter.scalding.cascading_interop.FlowListenerPromise
import com.twitter.scalding.filecache.{CachedFile, DistributedCacheFile}
import com.twitter.scalding.typed.functions.{ConsList, ReverseList}
import com.twitter.scalding.typed.cascading_backend.AsyncFlowDefRunner
import com.twitter.scalding.cascading_interop.FlowListenerPromise.FlowStopException
import com.stripe.dagon.{Memoize, RefPair}
import java.io.Serializable
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.SECONDS
import scala.concurrent.{
  blocking,
  duration,
  Await,
  ExecutionContext => ConcurrentExecutionContext,
  Future,
  Promise
}
import scala.util.{Failure, Success, Try}
import scala.util.hashing.MurmurHash3

/**
 * Execution[T] represents and computation that can be run and will produce a value T and keep track of
 * counters incremented inside of TypedPipes using a Stat.
 *
 * Execution[T] is the recommended way to compose multistep computations that involve branching (if/then),
 * intermediate calls to remote services, file operations, or looping (e.g. testing for convergence).
 *
 * Library functions are encouraged to implement functions from TypedPipes or ValuePipes to Execution[R] for
 * some result R. Refrain from calling run in library code. Let the caller of your library call run.
 *
 * Note this is a Monad, meaning flatMap composes in series as you expect. It is also an applicative functor,
 * which means zip (called join in some libraries) composes two Executions is parallel. Prefer zip to flatMap
 * if you want to run two Executions in parallel.
 */
sealed trait Execution[+T] extends Serializable { self: Product =>
  import Execution.{
    EvalCache,
    FlatMapped,
    GetCounters,
    Mapped,
    OnComplete,
    RecoverWith,
    ResetCounters,
    Zipped
  }

  /**
   * Lift an Execution into a Try
   *
   * When this function is called the Execution should never be failed instead only the Try.
   */
  def liftToTry: Execution[Try[T]] =
    map(e => Success(e)).recoverWith { case throwable => Execution.from(Failure(throwable)) }

  /**
   * Scala uses the filter method in for syntax for pattern matches that can fail. If this filter is false,
   * the result of run will be an exception in the future
   */
  def filter(pred: T => Boolean): Execution[T] =
    flatMap {
      case good if pred(good) => Execution.from(good)
      case failed             => Execution.from(sys.error("Filter failed on: " + failed.toString))
    }

  /**
   * First run this Execution, then move to the result of the function
   */
  def flatMap[U](fn: T => Execution[U]): Execution[U] =
    FlatMapped(this, fn)

  /**
   * This is the same as flatMap(identity)
   */
  def flatten[U](implicit ev: T <:< Execution[U]): Execution[U] =
    flatMap(ev)

  /**
   * Apply a pure function to the result. This may not be called if subsequently the result is discarded with
   * .unit For side effects see onComplete.
   */
  def map[U](fn: T => U): Execution[U] =
    Mapped(this, fn)

  /**
   * Reads the counters into the value, but does not reset them. You may want .getAndResetCounters.
   */
  def getCounters: Execution[(T, ExecutionCounters)] =
    GetCounters(this)

  /**
   * Reads the counters and resets them to zero. Probably what you want in a loop that is using counters to
   * check for convergence.
   */
  def getAndResetCounters: Execution[(T, ExecutionCounters)] =
    getCounters.resetCounters

  /**
   * This function is called when the current run is completed. This is only a side effect (see unit return).
   *
   * ALSO You must .run the result. If you throw away the result of this call, your fn will never be called.
   * When you run the result, the Future you get will not be complete unless fn has completed running. If fn
   * throws, it will be handled be the scala.concurrent.ExecutionContext.reportFailure NOT by returning a
   * Failure in the Future.
   */
  def onComplete(fn: Try[T] => Unit): Execution[T] = OnComplete(this, fn)

  /**
   * This allows you to handle a failure by giving a replacement execution in some cases. This execution may
   * be a retry if you know that your execution can have spurious errors, or it could be a constant or an
   * alternate way to compute. Be very careful creating looping retries that could hammer your cluster when
   * the data is missing or when when there is some real problem with your job logic.
   */
  def recoverWith[U >: T](rec: PartialFunction[Throwable, Execution[U]]): Execution[U] =
    RecoverWith(this, rec)

  /**
   * Resets the counters back to zero. This is useful if you want to reset before a zip or a call to flatMap
   */
  def resetCounters: Execution[T] =
    ResetCounters(this)

  /**
   * This causes the Execution to occur. The result is not cached, so each call to run will result in the
   * computation being re-run. Avoid calling this until the last possible moment by using flatMap, zip and
   * recoverWith.
   *
   * Seriously: pro-style is for this to be called only once in a program.
   */
  final def run(conf: Config, mode: Mode)(implicit cec: ConcurrentExecutionContext): Future[T] = {
    val writer: Execution.Writer = mode.newWriter()
    val ec = new EvalCache(writer)
    val confWithId = conf.setScaldingExecutionId(UUID.randomUUID.toString)

    val exec = Execution.optimize(conf, this)
    // get on Trampoline
    val CFuture(fut, cancelHandler) = exec.runStats(confWithId, mode, ec)(cec).get
    // When the final future in complete we stop the submit thread
    val result = fut.map(_._1).andThen { case t =>
      if (t.isFailure) {
        blocking {
          // cancel running executions if this was a failure
          Await.ready(cancelHandler.stop(), duration.Duration(30, SECONDS))
        }
      }
      writer.finished()
    }
    // wait till the end to start the thread in case the above throws
    writer.start()
    result
  }

  /**
   * This is the internal method that must be implemented Given a config, mode, and cache of evaluations for
   * this config and mode, return the new cache with as much evaluation as possible before the future
   * completes, and a future of the result, counters and cache after the future is complete
   */
  protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
      cec: ConcurrentExecutionContext
  ): Trampoline[CFuture[(T, Map[Long, ExecutionCounters])]]

  /**
   * This is convenience for when we don't care about the result. like .map(_ => ())
   */
  def unit: Execution[Unit] = map(_ => ())

  /**
   * This waits synchronously on run, using the global execution context Avoid calling this if possible,
   * prefering run or just Execution composition. Every time someone calls this, be very suspect. It is always
   * code smell. Very seldom should you need to wait on a future.
   */
  def waitFor(conf: Config, mode: Mode): Try[T] =
    Try(Await.result(run(conf, mode)(ConcurrentExecutionContext.global), duration.Duration.Inf))

  /**
   * This is here to silence warnings in for comprehensions, but is identical to .filter.
   *
   * Users should never directly call this method, call .filter
   */
  def withFilter(p: T => Boolean): Execution[T] = filter(p)
  /*
   * run this and that in parallel, without any dependency. This will
   * be done in a single cascading flow if possible.
   */
  def zip[U](that: Execution[U]): Execution[(T, U)] =
    Zipped(this, that)

  override val hashCode: Int = MurmurHash3.productHash(self)

  /**
   * since executions, particularly Zips can cause two executions to merge we can have exponential cost to
   * computing equals if we are not careful
   */
  override def equals(other: Any): Boolean =
    other match {
      case otherEx: Execution[_] =>
        if (otherEx eq this) true
        else if (otherEx.hashCode != hashCode) false
        else {
          // If we get here, we have two executions that either
          // collide in hashcode, or they are truely equal. Since
          // collisions are rare, most of these will be true equality
          // so we will fully walk the graph. If we don't remember
          // the branches we go down, Zipped will be very expensize
          import Execution._
          val fn = Memoize.function[RefPair[Execution[Any], Execution[Any]], Boolean] {
            case (RefPair(a, b), _) if a eq b => true
            case (RefPair(FlatMapped(ex0, fn0), FlatMapped(ex1, fn1)), rec) =>
              (fn0 == fn1) && rec(RefPair(ex0, ex1))
            case (RefPair(FlowDefExecution(fn0), FlowDefExecution(fn1)), rec) =>
              fn0 == fn1
            case (RefPair(FutureConst(fn0), FutureConst(fn1)), rec) =>
              fn0 == fn1
            case (RefPair(GetCounters(ex0), GetCounters(ex1)), rec) =>
              rec(RefPair(ex0, ex1))
            case (RefPair(Mapped(ex0, fn0), Mapped(ex1, fn1)), rec) =>
              (fn0 == fn1) && rec(RefPair(ex0, ex1))
            case (RefPair(OnComplete(ex0, fn0), OnComplete(ex1, fn1)), rec) =>
              (fn0 == fn1) && rec(RefPair(ex0, ex1))
            case (RefPair(ReaderExecution, ReaderExecution), _) => true
            case (RefPair(RecoverWith(ex0, fn0), RecoverWith(ex1, fn1)), rec) =>
              (fn0 == fn1) && rec(RefPair(ex0, ex1))
            case (RefPair(ResetCounters(ex0), ResetCounters(ex1)), rec) =>
              rec(RefPair(ex0, ex1))
            case (RefPair(TransformedConfig(ex0, fn0), TransformedConfig(ex1, fn1)), rec) =>
              (fn0 == fn1) && rec(RefPair(ex0, ex1))
            case (RefPair(UniqueIdExecution(fn0), UniqueIdExecution(fn1)), _) =>
              fn0 == fn1
            case (RefPair(WithNewCache(ex0), WithNewCache(ex1)), rec) =>
              rec(RefPair(ex0, ex1))
            case (RefPair(WriteExecution(h0, t0, f0), WriteExecution(h1, t1, f1)), _) =>
              (f0 == f1) && ((h0 :: t0) == (h1 :: t1))
            case (RefPair(Zipped(a0, b0), Zipped(a1, b1)), rec) =>
              rec(RefPair(a0, a1)) && rec(RefPair(b0, b1))
            case (rp, _) =>
              require(rp._1.getClass != rp._2.getClass)
              false // the executions are not of the same type
          }
          fn(RefPair(this, otherEx))
        }
      case _ => false
    }
}

/**
 * Execution has many methods for creating Execution[T] instances, which are the preferred way to compose
 * computations in scalding libraries.
 */
object Execution {
  private[Execution] class AsyncSemaphore(initialPermits: Int = 0) {
    private[this] val waiters = new mutable.Queue[() => Unit]
    private[this] var availablePermits = initialPermits

    private[Execution] class SemaphorePermit {
      def release() =
        AsyncSemaphore.this.synchronized {
          availablePermits += 1
          if (availablePermits > 0 && waiters.nonEmpty) {
            availablePermits -= 1
            waiters.dequeue()()
          }
        }
    }

    def acquire(): Future[SemaphorePermit] = {
      val promise = Promise[SemaphorePermit]()

      def setAcquired(): Unit =
        promise.success(new SemaphorePermit)

      synchronized {
        if (availablePermits > 0) {
          availablePermits -= 1
          setAcquired()
        } else {
          waiters.enqueue(setAcquired)
        }
      }

      promise.future
    }
  }

  private def optimize[A](conf: Config, ex: Execution[A]): Execution[A] =
    if (conf.getExecutionOptimization) {
      ExecutionOptimizationRules.stdOptimizations(ex)
    } else {
      ex
    }

  /**
   * This is an instance of Monad for execution so it can be used in functions that apply to all Monads
   */
  implicit object ExecutionMonad extends Monad[Execution] {
    override def apply[T](t: T): Execution[T] = Execution.from(t)
    override def map[T, U](e: Execution[T])(fn: T => U): Execution[U] = e.map(fn)
    override def flatMap[T, U](e: Execution[T])(fn: T => Execution[U]): Execution[U] = e.flatMap(fn)
    override def join[T, U](t: Execution[T], u: Execution[U]): Execution[(T, U)] = t.zip(u)
  }

  def withConfig[T](ex: Execution[T])(c: Config => Config): Execution[T] =
    TransformedConfig(ex, c)

  /**
   * Distributes the file onto each map/reduce node, so you can use it for Scalding source creation and
   * TypedPipe, KeyedList, etc. transformations. Using the [[com.twitter.scalding.filecache.CachedFile]]
   * outside of Execution will probably not work.
   *
   * For multiple files you must nested your execution, see docs of
   * [[com.twitter.scalding.filecache.DistributedCacheFile]]
   */
  def withCachedFile[T](path: String)(fn: CachedFile => Execution[T]): Execution[T] =
    Execution.getMode.flatMap { mode =>
      val cachedFile = DistributedCacheFile.cachedFile(path, mode)

      withConfig(fn(cachedFile))(_.addDistributedCacheFiles(cachedFile))
    }

  /**
   * This function allows running the passed execution with its own cache. This will mean anything inside
   * won't benefit from Execution's global attempts to avoid repeated executions.
   *
   * The main use case here is when generating a lot of Execution results which are large. Executions caching
   * in this case can lead to out of memory errors as the cache keeps references to many heap objects.
   *
   * Ex. (0 until 1000).map { _ => Execution.withNewCache(myLargeObjectProducingExecution)}
   */
  def withNewCache[T](ex: Execution[T]): Execution[T] =
    WithNewCache(ex)

  /**
   * This is the standard semigroup on an Applicative (zip, then inside the Execution do plus)
   */
  implicit def semigroup[T: Semigroup]: Semigroup[Execution[T]] = Semigroup.from[Execution[T]] { (a, b) =>
    a.zip(b).map { case (ta, tb) => Semigroup.plus(ta, tb) }
  }

  /**
   * This is the standard monoid on an Applicative (zip, then inside the Execution do plus) useful to combine
   * unit Executions: Monoid.sum(ex1, ex2, ex3, ex4): Execution[Unit] where each are exi are Execution[Unit]
   */
  implicit def monoid[T: Monoid]: Monoid[Execution[T]] = Monoid.from(Execution.from(Monoid.zero[T])) {
    (a, b) =>
      a.zip(b).map { case (ta, tb) => Monoid.plus(ta, tb) }
  }

  /**
   * This is a mutable state that is kept internal to an execution as it is evaluating.
   */
  private[scalding] class EvalCache(val writer: Execution.Writer) {

    type Counters = Map[Long, ExecutionCounters]
    private[this] val cache =
      new FutureCacheGeneric[(Config, Execution[Any]), (Any, Counters), CPromise, CFuture]
    private[this] val toWriteCache = new FutureCacheGeneric[(Config, ToWrite[_]), Counters, CPromise, CFuture]

    // This method with return a 'clean' cache, that shares
    // the underlying thread and message queue of the parent evalCache
    def cleanCache: EvalCache = new EvalCache(writer)

    def getOrLock(cfg: Config, write: ToWrite[_]): Either[CPromise[Counters], CFuture[Counters]] =
      toWriteCache.getOrPromise((cfg, write))

    def getOrElseInsertWithFeedback[T](
        cfg: Config,
        ex: Execution[T],
        res: => CFuture[(T, Counters)]
    ): (Boolean, CFuture[(T, Counters)]) =
      // This cast is safe because we always insert with match T types
      cache
        .getOrElseUpdateIsNew((cfg, ex), res)
        .asInstanceOf[(Boolean, CFuture[(T, Counters)])]

    def getOrElseInsert[T](
        cfg: Config,
        ex: Execution[T],
        res: => CFuture[(T, Counters)]
    ): CFuture[(T, Counters)] =
      getOrElseInsertWithFeedback(cfg, ex, res)._2
  }

  private[scalding] final case class FutureConst[T](get: ConcurrentExecutionContext => Future[T])
      extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline {
        lazy val fut = for {
          futt <- toFuture(Try(get(cec)))
          t <- futt
        } yield (t, Map.empty[Long, ExecutionCounters])

        lazy val cfut = CFuture.uncancellable(fut)

        cache.getOrElseInsert(conf, this, cfut)
      }

    // Note that unit is not optimized away, since Futures are often used with side-effects, so,
    // we ensure that get is always called in contrast to Mapped, which assumes that fn is pure.
  }
  private[scalding] final case class FlatMapped[S, T](prev: Execution[S], fn: S => Execution[T])
      extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { case CFuture(fut1, cancelHandler1) =>
        lazy val uncachedCFut = for {
          (s, st1) <- fut1
          next0 = fn(s)
          // next0 has not been optimized yet, we need to try
          next = optimize(conf, next0)
        } yield {
          Trampoline.call(next.runStats(conf, mode, cache)).get.map { case (t, st2) =>
            (t, st1 ++ st2)
          }
        }

        val futCancel = cache.getOrElseInsert(conf, this, CFuture.fromFuture(uncachedCFut))

        CFuture(futCancel.future, cancelHandler1.compose(futCancel.cancellationHandler))
      }
  }

  private[scalding] final case class Mapped[S, T](prev: Execution[S], fn: S => T) extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { cfuture =>
        cache.getOrElseInsert(conf, this, cfuture.map { case (s, stats) => (fn(s), stats) })
      }
  }

  private[scalding] final case class GetCounters[T](prev: Execution[T])
      extends Execution[(T, ExecutionCounters)] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { cfuture =>
        cache.getOrElseInsert(
          conf,
          this,
          cfuture.map { case (t, c) =>
            val totalCount = Monoid.sum(c.map(_._2))
            ((t, totalCount), c)
          }
        )
      }
  }
  private[scalding] final case class ResetCounters[T](prev: Execution[T]) extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { cfuture =>
        cache
          .getOrElseInsert(conf, this, cfuture.map { case (t, _) => (t, Map.empty[Long, ExecutionCounters]) })
      }
  }

  private[scalding] final case class TransformedConfig[T](prev: Execution[T], fn: Config => Config)
      extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) = {
      val mutatedConfig = fn(conf)
      Trampoline.call(prev.runStats(mutatedConfig, mode, cache))
    }
  }

  /**
   * This class allows running the passed execution with its own cache. This will mean anything inside won't
   * benefit from Execution's global attempts to avoid repeated executions.
   *
   * The main use case here is when generating a lot of Execution results which are large. Executions caching
   * in this case can lead to out of memory errors as the cache keeps references to many heap objects.
   *
   * We operate here by getting a copy of the super EvalCache, without its cache's. This is so we can share
   * the singleton thread for scheduling jobs against Cascading.
   */
  private[scalding] final case class WithNewCache[T](prev: Execution[T]) extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) = {
      val ec = cache.cleanCache
      Trampoline.call(prev.runStats(conf, mode, ec))
    }
  }

  private[scalding] final case class OnComplete[T](prev: Execution[T], fn: Try[T] => Unit)
      extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { cfuture =>
        cache.getOrElseInsert(
          conf,
          this,
          cfuture.mapFuture { fut =>
            /**
             * The result we give is only completed AFTER fn is run so callers can wait on the result of this
             * OnComplete
             */
            val finished = Promise[(T, Map[Long, ExecutionCounters])]()
            fut.onComplete { tryT =>
              try {
                fn(tryT.map(_._1))
              } finally {
                // Do our best to signal when we are done
                finished.complete(tryT)
              }
            }
            finished.future
          }
        )
      }
  }

  private[scalding] final case class RecoverWith[T](
      prev: Execution[T],
      fn: PartialFunction[Throwable, Execution[T]]
  ) extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline.call(prev.runStats(conf, mode, cache)).map { case CFuture(fut, cancelHandler) =>
        lazy val uncachedFut = {
          fut
            .map(v => (v, CancellationHandler.empty)) // map this to the right shape
            .recoverWith {
              val flowStop: PartialFunction[Throwable, Future[Nothing]] = {
                case t: FlowStopException => // do not recover when the flow was stopped
                  Future.failed(t)
              }

              flowStop.orElse(fn.andThen { ex0 =>
                // we haven't optimized ex0 yet
                val ex = optimize(conf, ex0)
                val CFuture(f, c) = ex.runStats(conf, mode, cache).get
                f.map(v => (v, c))
              })
            }
        }

        val recoveredFut = cache.getOrElseInsert(
          conf,
          this,
          CFuture(uncachedFut.map(_._1), CancellationHandler.fromFuture(uncachedFut.map(_._2)))
        )
        CFuture(recoveredFut.future, cancelHandler.compose(recoveredFut.cancellationHandler))
      }
  }

  /**
   * Standard scala zip waits forever on the left side, even if the right side fails
   */
  def failFastZip[T, U](ft: Future[T], fu: Future[U])(implicit
      cec: ConcurrentExecutionContext
  ): Future[(T, U)] = {
    type State = Either[(T, Promise[U]), (U, Promise[T])]
    val middleState = Promise[State]()

    ft.onComplete {
      case f @ Failure(err) =>
        if (!middleState.tryFailure(err)) {
          // the right has already succeeded
          middleState.future.foreach {
            case Right((_, pt)) => pt.complete(f)
            case Left((t1, _)) => // This should never happen
              sys.error(s"Logic error: tried to set Failure($err) but Left($t1) already set")
          }
        }
      case Success(t) =>
        // Create the next promise:
        val pu = Promise[U]()
        if (!middleState.trySuccess(Left((t, pu)))) {
          // we can't set, so the other promise beat us here.
          middleState.future.foreach {
            case Right((_, pt)) => pt.success(t)
            case Left((t1, _)) => // This should never happen
              sys.error(s"Logic error: tried to set Left($t) but Left($t1) already set")
          }
        }
    }
    fu.onComplete {
      case f @ Failure(err) =>
        if (!middleState.tryFailure(err)) {
          // we can't set, so the other promise beat us here.
          middleState.future.foreach {
            case Left((_, pu)) => pu.complete(f)
            case Right((u1, _)) => // This should never happen
              sys.error(s"Logic error: tried to set Failure($err) but Right($u1) already set")
          }
        }
      case Success(u) =>
        // Create the next promise:
        val pt = Promise[T]()
        if (!middleState.trySuccess(Right((u, pt)))) {
          // we can't set, so the other promise beat us here.
          middleState.future.foreach {
            case Left((_, pu)) => pu.success(u)
            case Right((u1, _)) => // This should never happen
              sys.error(s"Logic error: tried to set Right($u) but Right($u1) already set")
          }
        }
    }

    middleState.future.flatMap {
      case Left((t, pu))  => pu.future.map((t, _))
      case Right((u, pt)) => pt.future.map((_, u))
    }
  }

  private[scalding] final case class Zipped[S, T](one: Execution[S], two: Execution[T])
      extends Execution[(S, T)] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      for {
        futCancel1 <- Trampoline.call(one.runStats(conf, mode, cache))
        futCancel2 <- Trampoline.call(two.runStats(conf, mode, cache))
      } yield {
        cache.getOrElseInsert(
          conf,
          this,
          futCancel1.zip(futCancel2).map { case ((s, ss), (t, st)) => ((s, t), ss ++ st) }
        )
      }
  }
  private[scalding] final case class UniqueIdExecution[T](fn: UniqueID => Execution[T]) extends Execution[T] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline(
        cache.getOrElseInsert(
          conf,
          this, {
            val (uid, nextConf) = conf.ensureUniqueId
            val next0 = fn(uid)
            // next0 has not been optimized yet, we need to try
            val next = optimize(conf, next0)
            next.runStats(nextConf, mode, cache).get
          }
        )
      )
  }
  /*
   * This allows you to run any cascading flowDef as an Execution.
   */
  private[scalding] final case class FlowDefExecution(result: (Config, Mode) => FlowDef)
      extends Execution[Unit] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline(
        cache.getOrElseInsert(
          conf,
          this,
          cache.writer match {
            case ar: AsyncFlowDefRunner =>
              ar.validateAndRun(conf)(result(_, mode)).map(m => ((), Map(m)))
            case other =>
              CFuture.failed(
                new IllegalArgumentException(
                  s"requires cascading Mode producing AsyncFlowDefRunner, found mode: $mode and writer ${other.getClass}: $other"
                )
              )
          }
        )
      )
  }

  /*
   * This is here so we can call without knowing the type T
   * but with proof that pipe matches sink
   *
   * We capture these objects in calls of TypedPipe.toIterableExecution,
   * but can safely ignore serializing planning objects for the same reasons mentioned in KryoHadoop.scala
   */

  sealed trait ToWrite[T] extends Serializable {
    def pipe: TypedPipe[T]
    def replacePipe(p: TypedPipe[T]): ToWrite[T] =
      this match {
        case ToWrite.Force(_)             => ToWrite.Force(p)
        case ToWrite.ToIterable(_)        => ToWrite.ToIterable(p)
        case ToWrite.SimpleWrite(_, sink) => ToWrite.SimpleWrite(p, sink)
      }
  }
  object ToWrite extends Serializable {
    final case class Force[T](@transient pipe: TypedPipe[T]) extends ToWrite[T]
    final case class ToIterable[T](@transient pipe: TypedPipe[T]) extends ToWrite[T]
    final case class SimpleWrite[T](@transient pipe: TypedPipe[T], @transient sink: TypedSink[T])
        extends ToWrite[T]

    final case class OptimizedWrite[F[_], T](@transient original: F[T], toWrite: ToWrite[T])

    /**
     * Optimize these writes into new writes and provide a mapping from the original TypedPipe to the new
     * TypedPipe
     */
    def optimizeWriteBatch(
        writes: List[ToWrite[_]],
        rules: Seq[Rule[TypedPipe]]
    ): List[OptimizedWrite[TypedPipe, _]] = {
      val dag = Dag.empty(typed.OptimizationRules.toLiteral)
      val (d1, ws) = writes.foldLeft((dag, List.empty[OptimizedWrite[Id, _]])) { case ((dag, ws), toWrite) =>
        val (d1, id) = dag.addRoot(toWrite.pipe)
        (d1, OptimizedWrite(id, toWrite) :: ws)
      }
      // now we optimize the graph
      val d2 = d1.applySeq(rules)
      // convert back to TypedPipe:
      ws.foldLeft(List.empty[OptimizedWrite[TypedPipe, _]]) { case (tail, optWrite) =>
        def go[A](optWriteId: OptimizedWrite[Id, A]): OptimizedWrite[TypedPipe, A] = {
          val idA = optWriteId.original
          val origPipe = d1.evaluate(idA)
          val optPipe = d2.evaluate(idA)
          OptimizedWrite(original = origPipe, toWrite = optWriteId.toWrite.replacePipe(optPipe))
        }
        go(optWrite) :: tail
      }
    }
  }

  /**
   * Something that can handle a batch of writes that may be optimized before running. Return a unique Long
   * for each run and Counters
   */
  trait Writer {

    /**
     * This is called by an Execution to begin processing
     */
    def start(): Unit

    /**
     * This is called by an Execution to end processing
     */
    def finished(): Unit

    /**
     * do a batch of writes, possibly optimizing, and return a new unique Long.
     *
     * empty writes are legitmate and should still return a Long
     */
    def execute(conf: Config, writes: List[ToWrite[_]])(implicit
        cec: ConcurrentExecutionContext
    ): CFuture[(Long, ExecutionCounters)]

    /**
     * This should only be called after a call to execute
     */
    private[Execution] def getForced[T](
        conf: Config,
        initial: TypedPipe[T]
    )(implicit cec: ConcurrentExecutionContext): Future[TypedPipe[T]]

    /**
     * This should only be called after a call to execute
     */
    private[Execution] def getIterable[T](
        conf: Config,
        initial: TypedPipe[T]
    )(implicit cec: ConcurrentExecutionContext): Future[Iterable[T]]
  }

  /**
   * This is the fundamental execution that actually happens in TypedPipes, all the rest are based on on this
   * one. By keeping the Pipe and the Sink, can inspect the Execution DAG and optimize it later (a goal, but
   * not done yet).
   */
  private[scalding] final case class WriteExecution[T](
      head: ToWrite[_],
      tail: List[ToWrite[_]],
      result: ((Config, Mode, Writer, ConcurrentExecutionContext)) => Future[T]
  ) extends Execution[T] {

    /**
     * We override this here to enable inlining the zip optimization below.
     *
     * This is such an important optimization, that we apply it locally. It is a bit ugly to have it here and
     * in ExecutionOptimizationRules but since this is so important, we do so anyway.
     *
     * Note Execution optimizations are not always applied, they are something users can disable, which they
     * may since in some cases giant Execution graphs have seen stack overflows. It doesn't hurt to apply this
     * optimization here, but it doesn't cover all cases since it only combines adjacent writes.
     */
    override def map[U](mapFn: T => U): Execution[U] =
      WriteExecution(head, tail, ExecutionOptimizationRules.MapWrite.ComposeMap(result, mapFn))

    private def unwrapListEither[A, B, C](it: List[(A, Either[B, C])]): (List[(A, B)], List[(A, C)]) =
      it match {
        case (a, Left(b)) :: tail =>
          val (l, r) = unwrapListEither(tail)
          ((a, b) :: l, r)
        case (a, Right(c)) :: tail =>
          val (l, r) = unwrapListEither(tail)
          (l, (a, c) :: r)
        case Nil => (Nil, Nil)
      }

    // We look up to see if any of our ToWrite elements have already been ran
    // if so we remove them from the cache.
    // Anything not already ran we run as part of a single flow def, using their combined counters for the others
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) = {
      lazy val uncachedFutureCancel = {
        val cacheLookup: List[
          (
              ToWrite[_],
              (Either[CPromise[Map[Long, ExecutionCounters]], CFuture[Map[Long, ExecutionCounters]]])
          )
        ] =
          (head :: tail).map(tw => (tw, cache.getOrLock(conf, tw)))
        val (weDoOperation, someoneElseDoesOperation) = unwrapListEither(cacheLookup)

        val otherResult = CFuture.failFastSequence(someoneElseDoesOperation.map(_._2))

        otherResult.future.value match {
          case Some(Failure(e)) => CFuture.failed(e)
          case _ => // Either successful or not completed yet
            val localFlowDefCountersFuture: CFuture[Map[Long, ExecutionCounters]] =
              weDoOperation match {
                case all @ (h :: tail) =>
                  val CFuture(fut, cancelHandler) = cache.writer
                    .execute(conf, all.map(_._1))

                  val futCounters: Future[Map[Long, ExecutionCounters]] =
                    fut.map(Map(_))

                  // Complete all of the promises we put into the cache
                  // with this future counters set
                  all.foreach { case (toWrite, cpromise) =>
                    cpromise.completeWith(CFuture(futCounters, cancelHandler))
                  }
                  CFuture(futCounters, cancelHandler)
                case Nil =>
                  // No work to do, provide a fulled set of 0 counters to operate on
                  CFuture(Future.successful(Map.empty), CancellationHandler.empty)
              }
            val bothFutures = otherResult.zip(localFlowDefCountersFuture)

            val fut = for {
              (lCounters, fdCounters) <- bothFutures.future
              t <- result(
                (conf, mode, cache.writer, cec)
              ) // TODO do i need to do something here to make this cancellable?
              summedCounters = (fdCounters :: lCounters).reduce(_ ++ _)
            } yield (t, summedCounters)

            CFuture(fut, bothFutures.cancellationHandler)
        }
      }

      Trampoline(cache.getOrElseInsert(conf, this, uncachedFutureCancel))
    }

    /**
     * This is such an important optimization, that we apply it locally. It is a bit ugly to have it here and
     * in ExecutionOptimizationRules but since this is so important, we do so anyway.
     *
     * Note Execution optimizations are not always applied, they are something users can disable, which they
     * may since in some cases giant Execution graphs have seen stack overflows. It doesn't hurt to apply this
     * optimization here, but it doesn't cover all cases since it only combines adjacent writes.
     *
     * Note, each Write is individually cached so it won't happen twice, but it is usually better to compose
     * into the biggest set of writes so the planner can optimize the largest graph possible.
     *
     * run this and that in parallel, without any dependency. This will be done in a single cascading flow if
     * possible.
     *
     * If both sides are write executions then merge them
     */
    override def zip[U](that: Execution[U]): Execution[(T, U)] =
      that match {
        case w1 @ WriteExecution(_, _, _) =>
          ExecutionOptimizationRules.ZipWrite.mergeWrite(this, w1)
        case that => Zipped(this, that)
      }
  }

  /**
   * This is called Reader, because it just returns its input to run as the output
   */
  private[scalding] case object ReaderExecution extends Execution[(Config, Mode)] {
    protected def runStats(conf: Config, mode: Mode, cache: EvalCache)(implicit
        cec: ConcurrentExecutionContext
    ) =
      Trampoline(CFuture.successful(((conf, mode), Map.empty)))

    override def equals(that: Any): Boolean =
      that match {
        // this has to be here or we get an infinite loop in the default equals
        case _: ReaderExecution.type => true
        case _                       => false
      }
  }

  private def toFuture[R](t: Try[R]): Future[R] =
    t match {
      case Success(s)   => Future.successful(s)
      case Failure(err) => Future.failed(err)
    }

  /**
   * This creates a definitely failed Execution.
   */
  def failed(t: Throwable): Execution[Nothing] = fromTry(Failure(t))

  /**
   * This makes a constant execution that runs no job. Note this is a lazy parameter that is evaluated every
   * time run is called and does so in the ExecutionContext given to run
   */
  def from[T](t: => T): Execution[T] = fromFuture(implicit ec => Future(t))

  /**
   * This evaluates the argument every time run is called, and does so in the ExecutionContext given to run
   */
  def fromTry[T](t: => Try[T]): Execution[T] = fromFuture { implicit ec =>
    Future(t).flatMap(toFuture)
  }

  /**
   * The call to fn will happen when the run method on the result is called. The ConcurrentExecutionContext
   * will be the same one used on run. This is intended for cases where you need to make asynchronous calls in
   * the middle or end of execution. Presumably this is used with flatMap either before or after
   */
  def fromFuture[T](fn: ConcurrentExecutionContext => Future[T]): Execution[T] = FutureConst(fn)

  /** Returns a constant Execution[Unit] */
  val unit: Execution[Unit] = from(())

  /**
   * This converts a function into an Execution monad. The flowDef returned is never mutated.
   */
  def fromFn(fn: (Config, Mode) => FlowDef): Execution[Unit] =
    FlowDefExecution(fn)

  def forceToDisk[T](t: TypedPipe[T]): Execution[TypedPipe[T]] =
    WriteExecution(ToWrite.Force(t), Nil, { case (conf, _, w, cec) => w.getForced(conf, t)(cec) })

  def toIterable[T](t: TypedPipe[T]): Execution[Iterable[T]] =
    WriteExecution(ToWrite.ToIterable(t), Nil, { case (conf, _, w, cec) => w.getIterable(conf, t)(cec) })

  /**
   * The simplest form, just sink the typed pipe into the sink and get a unit execution back
   */
  private[scalding] def write[T](pipe: TypedPipe[T], sink: TypedSink[T]): Execution[Unit] =
    write(pipe, sink, ())

  private[scalding] def write[T, U](pipe: TypedPipe[T], sink: TypedSink[T], presentType: => U): Execution[U] =
    WriteExecution(ToWrite.SimpleWrite(pipe, sink), Nil, tup => Future(presentType)(tup._4))

  /**
   * Convenience method to get the Args
   */
  def getArgs: Execution[Args] = ReaderExecution.map(_._1.getArgs)

  /**
   * Use this to read the configuration, which may contain Args or options which describe input on which to
   * run
   */
  def getConfig: Execution[Config] = ReaderExecution.map(_._1)

  /** Use this to get the mode, which may contain the job conf */
  def getMode: Execution[Mode] = ReaderExecution.map(_._2)

  /** Use this to get the config and mode. */
  def getConfigMode: Execution[(Config, Mode)] = ReaderExecution

  /**
   * This is convenience method only here to make it slightly cleaner to get Args, which are in the Config
   */
  def withArgs[T](fn: Args => Execution[T]): Execution[T] =
    getConfig.flatMap(conf => fn(conf.getArgs))

  /**
   * Use this to use counters/stats with Execution. You do this: Execution.withId { implicit uid => val myStat
   * = Stat("myStat") // uid is implicitly pulled in pipe.map { t => if(someCase(t)) myStat.inc fn(t) }
   * .writeExecution(mySink) }
   */
  def withId[T](fn: UniqueID => Execution[T]): Execution[T] = UniqueIdExecution(fn)

  /*
   * This runs a Flow using Cascading's built in threads. The resulting JobStats
   * are put into a promise when they are ready
   */
  def run[C](flow: Flow[C]): Future[JobStats] =
    // This is in Java because of the cascading API's raw types on FlowListener
    FlowListenerPromise.start(flow, { f: Flow[C] => JobStats(f.getFlowStats) })
  private def run[L, C](label: L, flow: Flow[C]): Future[(L, JobStats)] =
    // This is in Java because of the cascading API's raw types on FlowListener
    FlowListenerPromise.start(flow, { f: Flow[C] => (label, JobStats(f.getFlowStats)) })

  /*
   * This blocks the current thread until the job completes with either success or
   * failure.
   */
  def waitFor[C](flow: Flow[C]): Try[JobStats] =
    Try {
      flow.complete()
      JobStats(flow.getStats)
    }

  /**
   * combine several executions and run them in parallel when .run is called
   */
  def zip[A, B](ax: Execution[A], bx: Execution[B]): Execution[(A, B)] =
    ax.zip(bx)

  /**
   * combine several executions and run them in parallel when .run is called
   */
  def zip[A, B, C](ax: Execution[A], bx: Execution[B], cx: Execution[C]): Execution[(A, B, C)] =
    ax.zip(bx).zip(cx).map { case ((a, b), c) => (a, b, c) }

  /**
   * combine several executions and run them in parallel when .run is called
   */
  def zip[A, B, C, D](
      ax: Execution[A],
      bx: Execution[B],
      cx: Execution[C],
      dx: Execution[D]
  ): Execution[(A, B, C, D)] =
    ax.zip(bx).zip(cx).zip(dx).map { case (((a, b), c), d) => (a, b, c, d) }

  /**
   * combine several executions and run them in parallel when .run is called
   */
  def zip[A, B, C, D, E](
      ax: Execution[A],
      bx: Execution[B],
      cx: Execution[C],
      dx: Execution[D],
      ex: Execution[E]
  ): Execution[(A, B, C, D, E)] =
    ax.zip(bx).zip(cx).zip(dx).zip(ex).map { case ((((a, b), c), d), e) => (a, b, c, d, e) }

  // Avoid recreating the empty Execution each time
  private val nil = from(Nil)

  /*
   * If you have many Executions, it is better to combine them with
   * zip than flatMap (which is sequential). sequence just calls
   * zip on each item in the input sequence.
   *
   * Note, despite the name, which is taken from the standard scala Future API,
   * these executions are executed in parallel: run is called on all at the
   * same time, not one after the other.
   */
  def sequence[T](exs: Seq[Execution[T]]): Execution[Seq[T]] = {
    @annotation.tailrec
    def go(xs: List[Execution[T]], acc: Execution[List[T]]): Execution[List[T]] = xs match {
      case Nil       => acc
      case h :: tail => go(tail, h.zip(acc).map(ConsList()))
    }
    // This pushes all of them onto a list, and then reverse to keep order
    go(exs.toList, nil).map(ReverseList())
  }

  /**
   * Run a sequence of executions but only permitting parallelism amount to run at the same time.
   *
   * @param executions
   *   List of executions to run
   * @param parallelism
   *   Number to run in parallel
   * @return
   *   Execution Seq
   */
  def withParallelism[T](executions: Seq[Execution[T]], parallelism: Int): Execution[Seq[T]] = {
    require(parallelism > 0, s"Parallelism must be > 0: $parallelism")

    val sem = new AsyncSemaphore(parallelism)

    def waitRun(e: Execution[T]): Execution[T] =
      Execution
        .fromFuture(_ => sem.acquire())
        .flatMap(p => e.liftToTry.map((_, p)))
        .onComplete {
          case Success((_, p)) => p.release()
          case Failure(ex)     => throw ex // should never happen or there is a logic bug
        }
        .flatMap { case (ex, _) => fromTry(ex) }

    Execution.sequence(executions.map(waitRun))
  }
}

/**
 * This represents the counters portion of the JobStats that are returned. Counters are just a vector of longs
 * with counter name, group keys.
 */
trait ExecutionCounters {

  /**
   * immutable set of the keys.
   */
  def keys: Set[StatKey]

  /**
   * Same as get(key).getOrElse(0L) Note if a counter is never incremented, get returns None. But you can't
   * tell 0L that comes from None vs. a counter that was incremented then decremented.
   */
  def apply(key: StatKey): Long = get(key).getOrElse(0L)

  /**
   * If the counter is present, return it.
   */
  def get(key: StatKey): Option[Long]
  def toMap: Map[StatKey, Long] = keys.map(k => (k, get(k).getOrElse(0L))).toMap
}

/**
 * The companion gives several ways to create ExecutionCounters from other CascadingStats, JobStats, or Maps
 */
object ExecutionCounters {

  /**
   * This is the zero of the ExecutionCounter Monoid
   */
  def empty: ExecutionCounters = new ExecutionCounters {
    def keys = Set.empty
    def get(key: StatKey) = None
    override def toMap = Map.empty
  }

  /**
   * Just gets the counters from the CascadingStats and ignores all the other fields present
   */
  def fromCascading(cs: cascading.stats.CascadingStats): ExecutionCounters = new ExecutionCounters {
    import scala.collection.JavaConverters._

    val keys = (for {
      group <- cs.getCounterGroups.asScala
      counter <- cs.getCountersFor(group).asScala
    } yield StatKey(counter, group)).toSet

    def get(k: StatKey) =
      if (keys(k)) {
        // Yes, cascading is reversed frow what we did in Stats. :/
        Some(cs.getCounterValue(k.group, k.counter))
      } else None
  }

  /**
   * Gets just the counters from the JobStats
   */
  def fromJobStats(js: JobStats): ExecutionCounters = {
    val counters = js.counters
    new ExecutionCounters {
      def keys = for {
        group <- counters.keySet
        counter <- counters(group).keys
      } yield StatKey(counter, group)

      def get(k: StatKey) = counters.get(k.group).flatMap(_.get(k.counter))
    }
  }

  /**
   * A Simple wrapper over a Map[StatKey, Long]
   */
  def fromMap(allValues: Map[StatKey, Long]): ExecutionCounters =
    new ExecutionCounters {
      def keys = allValues.keySet
      def get(k: StatKey) = allValues.get(k)
      override def toMap = allValues
    }

  /**
   * This allows us to merge the results of two computations. It just does pointwise addition.
   */
  implicit def monoid: Monoid[ExecutionCounters] = new Monoid[ExecutionCounters] {
    override def isNonZero(that: ExecutionCounters) = that.keys.nonEmpty
    def zero = ExecutionCounters.empty
    def plus(left: ExecutionCounters, right: ExecutionCounters) =
      fromMap((left.keys ++ right.keys).map(k => (k, left(k) + right(k))).toMap)
  }
}
