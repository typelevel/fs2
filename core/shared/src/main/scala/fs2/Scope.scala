package fs2

import fs2.Scope.Lease


/**
  * Scope represents a controlled block of execution of the stream to track resources acquired and released during
  * the interpretation of the Stream.
  *
  * Scope's methods are used to perform low-level actions on stream interpretation, such as leasing the resources.
  */
trait Scope[F[_]] {


  /**
    * Allows to lease resources of the current scope.
    *
    * Note that this will lease all the resources, and resources of all parents and children of this scope.
    *
    * If this scope is closed already, this will yield to None. Otherwise this returns `F` that when evaluated
    * will cancelLease of the leased resources, possibly invoking their finalization.
    *
    * Resource may be finalized during this being executed, but before `lease` is acquired on the resource.
    * In that case the already finalized resource won't be leased.
    *
    * As such this is important to be run only when all resources are known to be not finalized or not being
    * about to be finalized yet.
    *
    * Wehn this completes all resources available at that time have been successfully leased.
    *
    */
  def lease: F[Option[Lease[F]]]


}

object Scope {

  /**
    * Wraps leased resources from the scope of the other Stream.
    */
  trait Lease[F[_]] {

    /**
      * Cancels lease of the previously leased resources. This may actually run finalizers on some of the resources,
      * and if these fails, tresulting `F` will be evaluated to left side.
      * @return
      */
    def cancel: F[Either[Throwable, Unit]]

  }


}




