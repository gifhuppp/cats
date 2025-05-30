/*
 * Copyright (c) 2015 Typelevel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package cats
package instances

import cats.data.{Ior, ZipStream}
import cats.kernel.compat.scalaVersionSpecific.*
import cats.syntax.show.*

import scala.annotation.tailrec

@suppressUnusedImportWarningForScalaVersionSpecific
trait StreamInstances extends cats.kernel.instances.StreamInstances {

  implicit val catsStdInstancesForStream
    : Traverse[Stream] with Alternative[Stream] with Monad[Stream] with CoflatMap[Stream] with Align[Stream] =
    new Traverse[Stream] with Alternative[Stream] with Monad[Stream] with CoflatMap[Stream] with Align[Stream] {

      def empty[A]: Stream[A] = Stream.Empty

      def combineK[A](x: Stream[A], y: Stream[A]): Stream[A] = x #::: y

      override def fromIterableOnce[A](as: IterableOnce[A]): Stream[A] = as.iterator.toStream

      override def prependK[A](a: A, fa: Stream[A]): Stream[A] = a #:: fa

      def pure[A](x: A): Stream[A] = Stream(x)

      override def map[A, B](fa: Stream[A])(f: A => B): Stream[B] =
        fa.map(f)

      def flatMap[A, B](fa: Stream[A])(f: A => Stream[B]): Stream[B] =
        fa.flatMap(f)

      override def map2[A, B, Z](fa: Stream[A], fb: Stream[B])(f: (A, B) => Z): Stream[Z] =
        if (fb.isEmpty) Stream.empty // do O(1) work if fb is empty
        else fa.flatMap(a => fb.map(b => f(a, b))) // already O(1) if fa is empty

      override def map2Eval[A, B, Z](fa: Stream[A], fb: Eval[Stream[B]])(f: (A, B) => Z): Eval[Stream[Z]] =
        if (fa.isEmpty) Eval.now(Stream.empty) // no need to evaluate fb
        else fb.map(fb => map2(fa, fb)(f))

      def coflatMap[A, B](fa: Stream[A])(f: Stream[A] => B): Stream[B] =
        fa.tails.toStream.init.map(f)

      def foldLeft[A, B](fa: Stream[A], b: B)(f: (B, A) => B): B =
        fa.foldLeft(b)(f)

      def foldRight[A, B](fa: Stream[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        Now(fa).flatMap { s =>
          // Note that we don't use pattern matching to deconstruct the
          // stream, since that would needlessly force the tail.
          if (s.isEmpty) lb else f(s.head, Eval.defer(foldRight(s.tail, lb)(f)))
        }

      override def foldMap[A, B](fa: Stream[A])(f: A => B)(implicit B: Monoid[B]): B =
        B.combineAll(fa.iterator.map(f))

      def traverse[G[_], A, B](fa: Stream[A])(f: A => G[B])(implicit G: Applicative[G]): G[Stream[B]] =
        // We use foldRight to avoid possible stack overflows. Since
        // we don't want to return a Eval[_] instance, we call .value
        // at the end.
        foldRight(fa, Always(G.pure(Stream.empty[B]))) { (a, lgsb) =>
          G.map2Eval(f(a), lgsb)(_ #:: _)
        }.value

      override def mapWithIndex[A, B](fa: Stream[A])(f: (A, Int) => B): Stream[B] =
        fa.zipWithIndex.map(ai => f(ai._1, ai._2))

      override def zipWithIndex[A](fa: Stream[A]): Stream[(A, Int)] =
        fa.zipWithIndex

      def tailRecM[A, B](a: A)(fn: A => Stream[Either[A, B]]): Stream[B] = {
        val it: Iterator[B] = new Iterator[B] {
          var stack: Stream[Either[A, B]] = fn(a)
          var state: Either[Unit, Option[B]] = Left(())

          @tailrec
          def advance(): Unit =
            stack match {
              case Right(b) #:: tail =>
                stack = tail
                state = Right(Some(b))
              case Left(a) #:: tail =>
                stack = fn(a) #::: tail
                advance()
              case empty =>
                state = Right(None)
            }

          @tailrec
          def hasNext: Boolean =
            state match {
              case Left(()) =>
                advance()
                hasNext
              case Right(o) =>
                o.isDefined
            }

          @tailrec
          def next(): B =
            state match {
              case Left(()) =>
                advance()
                next()
              case Right(o) =>
                val b = o.get
                advance()
                b
            }
        }

        it.toStream
      }

      override def exists[A](fa: Stream[A])(p: A => Boolean): Boolean =
        fa.exists(p)

      override def forall[A](fa: Stream[A])(p: A => Boolean): Boolean =
        fa.forall(p)

      override def get[A](fa: Stream[A])(idx: Long): Option[A] = {
        @tailrec
        def go(idx: Long, s: Stream[A]): Option[A] =
          s match {
            case h #:: tail =>
              if (idx == 0L) Some(h) else go(idx - 1L, tail)
            case _ => None
          }
        if (idx < 0L) None else go(idx, fa)
      }

      override def isEmpty[A](fa: Stream[A]): Boolean = fa.isEmpty

      override def foldM[G[_], A, B](fa: Stream[A], z: B)(f: (B, A) => G[B])(implicit G: Monad[G]): G[B] = {
        def step(in: (Stream[A], B)): G[Either[(Stream[A], B), B]] = {
          val (s, b) = in
          if (s.isEmpty)
            G.pure(Right(b))
          else
            G.map(f(b, s.head)) { bnext =>
              Left((s.tail, bnext))
            }
        }

        G.tailRecM((fa, z))(step)
      }

      override def fold[A](fa: Stream[A])(implicit A: Monoid[A]): A = A.combineAll(fa)

      override def toList[A](fa: Stream[A]): List[A] = fa.toList

      override def toIterable[A](fa: Stream[A]): Iterable[A] = fa

      override def reduceLeftOption[A](fa: Stream[A])(f: (A, A) => A): Option[A] =
        fa.reduceLeftOption(f)

      override def find[A](fa: Stream[A])(f: A => Boolean): Option[A] = fa.find(f)

      override def algebra[A]: Monoid[Stream[A]] = new kernel.instances.StreamMonoid[A]

      override def collectFirst[A, B](fa: Stream[A])(pf: PartialFunction[A, B]): Option[B] = fa.collectFirst(pf)

      override def collectFirstSome[A, B](fa: Stream[A])(f: A => Option[B]): Option[B] =
        fa.collectFirst(Function.unlift(f))

      def functor: Functor[Stream] = this

      def align[A, B](fa: Stream[A], fb: Stream[B]): Stream[Ior[A, B]] =
        alignWith(fa, fb)(identity)

      override def alignWith[A, B, C](fa: Stream[A], fb: Stream[B])(f: Ior[A, B] => C): Stream[C] =
        Align.alignWithIterator[A, B, C](fa, fb)(f).toStream
    }

  implicit def catsStdShowForStream[A: Show]: Show[Stream[A]] =
    stream => if (stream.isEmpty) "Stream()" else s"Stream(${stream.head.show}, ?)"

  implicit def catsStdParallelForStreamZipStream: Parallel.Aux[Stream, ZipStream] =
    new Parallel[Stream] {
      type F[x] = ZipStream[x]

      def monad: Monad[Stream] = cats.instances.stream.catsStdInstancesForStream
      def applicative: Applicative[ZipStream] = ZipStream.catsDataAlternativeForZipStream

      def sequential: ZipStream ~> Stream =
        new (ZipStream ~> Stream) { def apply[A](a: ZipStream[A]): Stream[A] = a.value }

      def parallel: Stream ~> ZipStream =
        new (Stream ~> ZipStream) { def apply[A](v: Stream[A]): ZipStream[A] = new ZipStream(v) }
    }
}

private[instances] trait StreamInstancesBinCompat0 {
  implicit val catsStdTraverseFilterForStream: TraverseFilter[Stream] = new TraverseFilter[Stream] {
    val traverse: Traverse[Stream] = cats.instances.stream.catsStdInstancesForStream

    override def mapFilter[A, B](fa: Stream[A])(f: (A) => Option[B]): Stream[B] =
      fa.collect(Function.unlift(f))

    override def filter[A](fa: Stream[A])(f: (A) => Boolean): Stream[A] = fa.filter(f)

    override def filterNot[A](fa: Stream[A])(f: A => Boolean): Stream[A] = fa.filterNot(f)

    override def collect[A, B](fa: Stream[A])(f: PartialFunction[A, B]): Stream[B] = fa.collect(f)

    override def flattenOption[A](fa: Stream[Option[A]]): Stream[A] = fa.flatten

    def traverseFilter[G[_], A, B](fa: Stream[A])(f: (A) => G[Option[B]])(implicit G: Applicative[G]): G[Stream[B]] =
      traverse
        .foldRight(fa, Eval.now(G.pure(Stream.empty[B])))((x, xse) =>
          G.map2Eval(f(x), xse)((i, o) => i.fold(o)(_ #:: o))
        )
        .value

    override def filterA[G[_], A](fa: Stream[A])(f: (A) => G[Boolean])(implicit G: Applicative[G]): G[Stream[A]] =
      traverse
        .foldRight(fa, Eval.now(G.pure(Stream.empty[A])))((x, xse) =>
          G.map2Eval(f(x), xse)((b, stream) => if (b) x #:: stream else stream)
        )
        .value

  }
}
