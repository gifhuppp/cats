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

import cats.arrow.FunctionK
import cats.data.{Validated, ZipList, ZipVector}

/**
 * Some types that form a FlatMap, are also capable of forming an Apply that supports parallel composition.
 * The NonEmptyParallel type class allows us to represent this relationship.
 */
trait NonEmptyParallel[M[_]] extends Serializable {
  type F[_]

  /**
   * The Apply instance for F[_]
   */
  def apply: Apply[F]

  /**
   * The FlatMap instance for M[_]
   */
  def flatMap: FlatMap[M]

  /**
   * Natural Transformation from the parallel Apply F[_] to the sequential FlatMap M[_].
   */
  def sequential: F ~> M

  /**
   * Natural Transformation from the sequential FlatMap M[_] to the parallel Apply F[_].
   */
  def parallel: M ~> F

  /**
   * Like [[Apply.productR]], but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parProductR[A, B](ma: M[A])(mb: M[B]): M[B] =
    Parallel.parMap2(ma, mb)((_, b) => b)(this)

  @deprecated("Use parProductR instead.", "1.0.0-RC2")
  @inline private[cats] def parFollowedBy[A, B](ma: M[A])(mb: M[B]): M[B] = parProductR(ma)(mb)

  /**
   * Like [[Apply.productL]], but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parProductL[A, B](ma: M[A])(mb: M[B]): M[A] =
    Parallel.parMap2(ma, mb)((a, _) => a)(this)

  @deprecated("Use parProductL instead.", "1.0.0-RC2")
  @inline private[cats] def parForEffect[A, B](ma: M[A])(mb: M[B]): M[A] = parProductL(ma)(mb)

}

/**
 * Some types that form a Monad, are also capable of forming an Applicative that supports parallel composition.
 * The Parallel type class allows us to represent this relationship.
 */
trait Parallel[M[_]] extends NonEmptyParallel[M] {

  /**
   * The applicative instance for F[_]
   */
  def applicative: Applicative[F]

  /**
   * The monad instance for M[_]
   */
  def monad: Monad[M]

  override def apply: Apply[F] = applicative

  override def flatMap: FlatMap[M] = monad

  /**
   * Provides an `ApplicativeError[F, E]` instance for any F, that has a `Parallel.Aux[M, F]`
   * and a `MonadError[M, E]` instance.
   * I.e. if you have a type M[_], that supports parallel composition through type F[_],
   * then you can get `ApplicativeError[F, E]` from `MonadError[M, E]`.
   */
  def applicativeError[E](implicit E: MonadError[M, E]): ApplicativeError[F, E] =
    new ApplicativeError[F, E] {

      def raiseError[A](e: E): F[A] =
        parallel(MonadError[M, E].raiseError(e))

      def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A] = {
        val ma = E.handleErrorWith(sequential(fa))(e => sequential.apply(f(e)))
        parallel(ma)
      }

      def pure[A](x: A): F[A] = applicative.pure(x)

      def ap[A, B](ff: F[(A) => B])(fa: F[A]): F[B] = applicative.ap(ff)(fa)

      override def map[A, B](fa: F[A])(f: (A) => B): F[B] = applicative.map(fa)(f)

      override def product[A, B](fa: F[A], fb: F[B]): F[(A, B)] = applicative.product(fa, fb)

      override def map2[A, B, Z](fa: F[A], fb: F[B])(f: (A, B) => Z): F[Z] = applicative.map2(fa, fb)(f)

      override def map2Eval[A, B, Z](fa: F[A], fb: Eval[F[B]])(f: (A, B) => Z): Eval[F[Z]] =
        applicative.map2Eval(fa, fb)(f)

      override def unlessA[A](cond: Boolean)(f: => F[A]): F[Unit] = applicative.unlessA(cond)(f)

      override def whenA[A](cond: Boolean)(f: => F[A]): F[Unit] = applicative.whenA(cond)(f)
    }
}

object NonEmptyParallel extends ScalaVersionSpecificParallelInstances {
  type Aux[M[_], F0[_]] = NonEmptyParallel[M] { type F[x] = F0[x] }

  def apply[M[_], F[_]](implicit P: NonEmptyParallel.Aux[M, F]): NonEmptyParallel.Aux[M, F] = P
  def apply[M[_]](implicit P: NonEmptyParallel[M], D: DummyImplicit): NonEmptyParallel.Aux[M, P.F] = P

  implicit def catsParallelForEitherValidated[E: Semigroup]: Parallel.Aux[Either[E, *], Validated[E, *]] =
    cats.instances.either.catsParallelForEitherAndValidated[E]

  implicit def catsStdNonEmptyParallelForZipList: NonEmptyParallel.Aux[List, ZipList] =
    cats.instances.list.catsStdNonEmptyParallelForListZipList

  implicit def catsStdNonEmptyParallelForZipVector: NonEmptyParallel.Aux[Vector, ZipVector] =
    cats.instances.vector.catsStdNonEmptyParallelForVectorZipVector
}

object Parallel extends ParallelArityFunctions2 {
  type Aux[M[_], F0[_]] = Parallel[M] { type F[x] = F0[x] }

  def apply[M[_], F[_]](implicit P: Parallel.Aux[M, F]): Parallel.Aux[M, F] = P
  def apply[M[_]](implicit P: Parallel[M], D: DummyImplicit): Parallel.Aux[M, P.F] = P

  /**
   * Like `TraverseFilter#traverseFilter`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   *
   * Example:
   * {{{
   * scala> import cats.syntax.all._
   * scala> import cats.data._
   * scala> val list: List[Int] = List(1, 2, 3, 4)
   * scala> def validate(n: Int): EitherNec[String, Option[Int]] =
   *      | if (n > 100) Left(NonEmptyChain.one("Too large"))
   *      | else if (n % 3 =!= 0) Right(Some(n))
   *      | else Right(None)
   * scala> list.parTraverseFilter(validate)
   * res0: EitherNec[String, List[Int]] = Right(List(1, 2, 4))
   * }}}
   */
  def parTraverseFilter[T[_], M[_], A, B](
    ta: T[A]
  )(f: A => M[Option[B]])(implicit T: TraverseFilter[T], P: Parallel[M]): M[T[B]] = {
    val ftb: P.F[T[B]] = T.traverseFilter[P.F, A, B](ta)(a => P.parallel(f(a)))(P.applicative)

    P.sequential(ftb)
  }

  /**
   * Like `TraverseFilter#sequenceFilter`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   *
   * Example:
   * {{{
   * scala> import cats.syntax.all._
   * scala> import cats.data._
   * scala> val list: List[EitherNec[String, Option[Int]]] = List(Left(NonEmptyChain.one("Error")), Left(NonEmptyChain.one("Warning!")))
   * scala> list.parSequenceFilter
   * res0: EitherNec[String, List[Int]] = Left(Chain(Error, Warning!))
   * }}}
   */
  def parSequenceFilter[T[_], M[_], A](ta: T[M[Option[A]]])(implicit T: TraverseFilter[T], P: Parallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = T.traverseFilter[P.F, M[Option[A]], A](ta)(P.parallel.apply(_))(P.applicative)

    P.sequential(fta)
  }

  /**
   * Like `TraverseFilter#filterA`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   *
   * Example:
   * {{{
   * scala> import cats.syntax.all._
   * scala> import cats.data._
   * scala> val list: List[Int] = List(1, 2, 3, 4)
   * scala> def validate(n: Int): EitherNec[String, Boolean] =
   *      | if (n > 100) Left(NonEmptyChain.one("Too large"))
   *      | else Right(n % 3 =!= 0)
   * scala> list.parFilterA(validate)
   * res0: EitherNec[String, List[Int]] = Right(List(1, 2, 4))
   * }}}
   */
  def parFilterA[T[_], M[_], A](
    ta: T[A]
  )(f: A => M[Boolean])(implicit T: TraverseFilter[T], P: Parallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = T.filterA(ta)(a => P.parallel(f(a)))(P.applicative)

    P.sequential(fta)
  }

  /**
   * Like `Traverse[A].sequence`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parSequence[T[_]: Traverse, M[_], A](tma: T[M[A]])(implicit P: Parallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = Traverse[T].traverse(tma)(P.parallel.apply(_))(using P.applicative)
    P.sequential(fta)
  }

  /**
   * Like `Traverse[A].traverse`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parTraverse[T[_]: Traverse, M[_], A, B](ta: T[A])(f: A => M[B])(implicit P: Parallel[M]): M[T[B]] = {
    val gtb: P.F[T[B]] = Traverse[T].traverse(ta)(a => P.parallel(f(a)))(using P.applicative)
    P.sequential(gtb)
  }

  /**
   * Like `Traverse[A].flatTraverse`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parFlatTraverse[T[_]: Traverse: FlatMap, M[_], A, B](
    ta: T[A]
  )(f: A => M[T[B]])(implicit P: Parallel[M]): M[T[B]] = {
    val gtb: P.F[T[B]] = Traverse[T].flatTraverse(ta)(a => P.parallel(f(a)))(P.applicative, FlatMap[T])
    P.sequential(gtb)
  }

  /**
   * Like `Traverse[A].flatSequence`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parFlatSequence[T[_]: Traverse: FlatMap, M[_], A](
    tma: T[M[T[A]]]
  )(implicit P: Parallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = Traverse[T].flatTraverse(tma)(P.parallel.apply(_))(P.applicative, FlatMap[T])
    P.sequential(fta)
  }

  /**
   * Like `Foldable[A].sequenceVoid`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parSequenceVoid[T[_]: Foldable, M[_], A](tma: T[M[A]])(implicit P: Parallel[M]): M[Unit] = {
    val fu: P.F[Unit] = Foldable[T].traverseVoid(tma)(P.parallel.apply(_))(P.applicative)
    P.sequential(fu)
  }

  /**
   * Alias for `parSequenceVoid`.
   *
   * @deprecated this method should be considered as deprecated and replaced by `parSequenceVoid`.
   */
  def parSequence_[T[_]: Foldable, M[_], A](tma: T[M[A]])(implicit P: Parallel[M]): M[Unit] =
    parSequenceVoid(tma)

  /**
   * Like `Foldable[A].traverseVoid`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parTraverseVoid[T[_]: Foldable, M[_], A, B](ta: T[A])(f: A => M[B])(implicit P: Parallel[M]): M[Unit] = {
    val gtb: P.F[Unit] = Foldable[T].traverseVoid(ta)(a => P.parallel(f(a)))(P.applicative)
    P.sequential(gtb)
  }

  /**
   * Alias for `parTraverseVoid`.
   *
   * @deprecated this method should be considered as deprecated and replaced by `parTraverseVoid`.
   */
  def parTraverse_[T[_]: Foldable, M[_], A, B](ta: T[A])(f: A => M[B])(implicit P: Parallel[M]): M[Unit] =
    parTraverseVoid(ta)(f)

  def parUnorderedTraverse[T[_]: UnorderedTraverse, M[_], F[_]: CommutativeApplicative, A, B](
    ta: T[A]
  )(f: A => M[B])(implicit P: Parallel.Aux[M, F]): M[T[B]] =
    P.sequential(UnorderedTraverse[T].unorderedTraverse(ta)(a => P.parallel(f(a))))

  def parUnorderedSequence[T[_]: UnorderedTraverse, M[_], F[_]: CommutativeApplicative, A](
    ta: T[M[A]]
  )(implicit P: Parallel.Aux[M, F]): M[T[A]] =
    parUnorderedTraverse[T, M, F, M[A], A](ta)(Predef.identity)

  def parUnorderedFlatTraverse[T[_]: UnorderedTraverse: FlatMap, M[_], F[_]: CommutativeApplicative, A, B](
    ta: T[A]
  )(f: A => M[T[B]])(implicit P: Parallel.Aux[M, F]): M[T[B]] =
    P.monad.map(parUnorderedTraverse[T, M, F, A, T[B]](ta)(f))(FlatMap[T].flatten)

  def parUnorderedFlatSequence[T[_]: UnorderedTraverse: FlatMap, M[_], F[_]: CommutativeApplicative, A](
    ta: T[M[T[A]]]
  )(implicit P: Parallel.Aux[M, F]): M[T[A]] =
    parUnorderedFlatTraverse[T, M, F, M[T[A]], A](ta)(Predef.identity)

  /**
   * Like `NonEmptyTraverse[A].nonEmptySequence`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptySequence[T[_]: NonEmptyTraverse, M[_], A](
    tma: T[M[A]]
  )(implicit P: NonEmptyParallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = NonEmptyTraverse[T].nonEmptyTraverse(tma)(P.parallel.apply(_))(using P.apply)
    P.sequential(fta)
  }

  /**
   * Like `NonEmptyTraverse[A].nonEmptyTraverse`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptyTraverse[T[_]: NonEmptyTraverse, M[_], A, B](
    ta: T[A]
  )(f: A => M[B])(implicit P: NonEmptyParallel[M]): M[T[B]] = {
    val gtb: P.F[T[B]] = NonEmptyTraverse[T].nonEmptyTraverse(ta)(a => P.parallel(f(a)))(using P.apply)
    P.sequential(gtb)
  }

  /**
   * Like `NonEmptyTraverse[A].nonEmptyFlatTraverse`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptyFlatTraverse[T[_]: NonEmptyTraverse: FlatMap, M[_], A, B](
    ta: T[A]
  )(f: A => M[T[B]])(implicit P: NonEmptyParallel[M]): M[T[B]] = {
    val gtb: P.F[T[B]] =
      NonEmptyTraverse[T].nonEmptyFlatTraverse(ta)(a => P.parallel(f(a)))(P.apply, FlatMap[T])
    P.sequential(gtb)
  }

  /**
   * Like `NonEmptyTraverse[A].nonEmptyFlatSequence`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptyFlatSequence[T[_]: NonEmptyTraverse: FlatMap, M[_], A](
    tma: T[M[T[A]]]
  )(implicit P: NonEmptyParallel[M]): M[T[A]] = {
    val fta: P.F[T[A]] = NonEmptyTraverse[T].nonEmptyFlatTraverse(tma)(P.parallel.apply(_))(P.apply, FlatMap[T])
    P.sequential(fta)
  }

  /**
   * Like `Reducible[A].nonEmptySequenceVoid`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptySequenceVoid[T[_]: Reducible, M[_], A](
    tma: T[M[A]]
  )(implicit P: NonEmptyParallel[M]): M[Unit] = {
    val fu: P.F[Unit] = Reducible[T].nonEmptyTraverseVoid(tma)(P.parallel.apply(_))(P.apply)
    P.sequential(fu)
  }

  /**
   * Alias for `parNonEmptySequenceVoid`.
   *
   * @deprecated this method should be considered as deprecated and replaced by `parNonEmptySequenceVoid`.
   */
  def parNonEmptySequence_[T[_]: Reducible, M[_], A](
    tma: T[M[A]]
  )(implicit P: NonEmptyParallel[M]): M[Unit] =
    parNonEmptySequenceVoid[T, M, A](tma)

  /**
   * Like `Reducible[A].nonEmptyTraverseVoid`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parNonEmptyTraverseVoid[T[_]: Reducible, M[_], A, B](
    ta: T[A]
  )(f: A => M[B])(implicit P: NonEmptyParallel[M]): M[Unit] = {
    val gtb: P.F[Unit] = Reducible[T].nonEmptyTraverseVoid(ta)(a => P.parallel(f(a)))(P.apply)
    P.sequential(gtb)
  }

  /**
   * Alias for `parNonEmptyTraverseVoid`.
   *
   * @deprecated this method should be considered as deprecated and replaced by `parNonEmptyTraverseVoid`.
   */
  def parNonEmptyTraverse_[T[_]: Reducible, M[_], A, B](
    ta: T[A]
  )(f: A => M[B])(implicit P: NonEmptyParallel[M]): M[Unit] =
    parNonEmptyTraverseVoid[T, M, A, B](ta)(f)

  /**
   * Like `Bitraverse[A].bitraverse`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parBitraverse[T[_, _]: Bitraverse, M[_], A, B, C, D](
    tab: T[A, B]
  )(f: A => M[C], g: B => M[D])(implicit P: Parallel[M]): M[T[C, D]] = {
    val ftcd: P.F[T[C, D]] =
      Bitraverse[T].bitraverse(tab)(a => P.parallel(f(a)), b => P.parallel(g(b)))(using P.applicative)
    P.sequential(ftcd)
  }

  /**
   * Like `Bitraverse[A].bisequence`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parBisequence[T[_, _]: Bitraverse, M[_], A, B](
    tmamb: T[M[A], M[B]]
  )(implicit P: Parallel[M]): M[T[A, B]] = {
    val ftab: P.F[T[A, B]] =
      Bitraverse[T].bitraverse(tmamb)(P.parallel.apply(_), P.parallel.apply(_))(using P.applicative)
    P.sequential(ftab)
  }

  /**
   * Like `Bitraverse[A].leftTraverse`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parLeftTraverse[T[_, _]: Bitraverse, M[_], A, B, C](
    tab: T[A, B]
  )(f: A => M[C])(implicit P: Parallel[M]): M[T[C, B]] = {
    val ftcb: P.F[T[C, B]] =
      Bitraverse[T].bitraverse(tab)(a => P.parallel.apply(f(a)), P.applicative.pure(_))(using P.applicative)
    P.sequential(ftcb)
  }

  /**
   * Like `Bitraverse[A].leftSequence`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parLeftSequence[T[_, _]: Bitraverse, M[_], A, B](
    tmab: T[M[A], B]
  )(implicit P: Parallel[M]): M[T[A, B]] = {
    val ftab: P.F[T[A, B]] =
      Bitraverse[T].bitraverse(tmab)(P.parallel.apply(_), P.applicative.pure(_))(using P.applicative)
    P.sequential(ftab)
  }

  /**
   * Like `Foldable[A].foldMapA`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parFoldMapA[T[_], M[_], A, B](
    ta: T[A]
  )(f: A => M[B])(implicit T: Foldable[T], P: Parallel[M], B: Monoid[B]): M[B] = {
    val fb: P.F[B] =
      Foldable[T].foldMapA(ta)(a => P.parallel(f(a)))(P.applicative, B)
    P.sequential(fb)
  }

  /**
   * Like `Reducible[A].reduceMapA`, but uses the apply instance corresponding
   * to the `NonEmptyParallel` instance instead.
   */
  def parReduceMapA[T[_], M[_], A, B](
    ta: T[A]
  )(f: A => M[B])(implicit T: Reducible[T], P: NonEmptyParallel[M], B: Semigroup[B]): M[B] = {
    val fb: P.F[B] =
      T.reduceMapA(ta)(a => P.parallel(f(a)))(P.apply, B)
    P.sequential(fb)
  }

  /**
   * Like `Applicative[F].ap`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parAp[M[_], A, B](mf: M[A => B])(ma: M[A])(implicit P: NonEmptyParallel[M]): M[B] =
    P.sequential(P.apply.ap(P.parallel(mf))(P.parallel(ma)))

  /**
   * Like `Applicative[F].product`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parProduct[M[_], A, B](ma: M[A], mb: M[B])(implicit P: NonEmptyParallel[M]): M[(A, B)] =
    P.sequential(P.apply.product(P.parallel(ma), P.parallel(mb)))

  /**
   * Like `Applicative[F].ap2`, but uses the applicative instance
   * corresponding to the Parallel instance instead.
   */
  def parAp2[M[_], A, B, Z](ff: M[(A, B) => Z])(ma: M[A], mb: M[B])(implicit P: NonEmptyParallel[M]): M[Z] =
    P.sequential(
      P.apply.ap2(P.parallel(ff))(P.parallel(ma), P.parallel(mb))
    )

  /**
   * Like `Applicative[F].replicateA`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parReplicateA[M[_], A](n: Int, ma: M[A])(implicit P: Parallel[M]): M[List[A]] =
    P.sequential(P.applicative.replicateA(n, P.parallel(ma)))

  /**
   * Like `Applicative[F].replicateA_`, but uses the apply instance
   * corresponding to the Parallel instance instead.
   */
  def parReplicateA_[M[_], A](n: Int, ma: M[A])(implicit P: Parallel[M]): M[Unit] =
    P.sequential(P.applicative.replicateA_(n, P.parallel(ma)))

  /**
   * Provides an `ApplicativeError[F, E]` instance for any F, that has a `Parallel.Aux[M, F]`
   * and a `MonadError[M, E]` instance.
   * I.e. if you have a type M[_], that supports parallel composition through type F[_],
   * then you can get `ApplicativeError[F, E]` from `MonadError[M, E]`.
   */
  def applicativeError[M[_], E](implicit P: Parallel[M], E: MonadError[M, E]): ApplicativeError[P.F, E] =
    P.applicativeError[E]

  /**
   * A Parallel instance for any type `M[_]` that supports parallel composition through itself.
   * Can also be used for giving `Parallel` instances to types that do not support parallel composition,
   * but are required to have an instance of `Parallel` defined,
   * in which case parallel composition will actually be sequential.
   */
  def identity[M[_]: Monad]: Parallel.Aux[M, M] =
    new Parallel[M] {
      type F[x] = M[x]

      val monad: Monad[M] = implicitly[Monad[M]]

      val applicative: Applicative[M] = implicitly[Monad[M]]

      val sequential: M ~> M = FunctionK.id

      val parallel: M ~> M = FunctionK.id
    }
}
