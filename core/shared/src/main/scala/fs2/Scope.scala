package fs2

/**
 * Represents a period of stream execution in which resources are acquired and released.
 *
 * Note: this type is generally used to implement low-level actions that manipulate
 * resource lifetimes and hence, isn't generally used by user-level code.
 */
abstract class Scope[F[_]] {

  /**
   * Leases the resources of this scope until the returned lease is cancelled.
   *
   * Note that this leases all resources in this scope, resources in all parent scopes (up to root)
   * and resources of all child scopes.
   *
   * `None` is returned if this scope is already closed. Otherwise a lease is returned,
   * which must be cancelled. Upon cancellation, resource finalizers may be run, depending on the
   * state of the owning scopes.
   *
   * Resources may be finalized during the execution of this method and before the lease has been acquired
   * for a resource. In such an event, the already finalized resource won't be leased. As such, it is
   * important to call `lease` only when all resources are known to be non-finalized / non-finalizing.
   *
   * When the lease is returned, all resources available at the time `lease` was called have been
   * successfully leased.
   */
  def lease: F[Option[Lease[F]]]
}
