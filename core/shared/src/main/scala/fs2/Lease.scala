  package fs2

/**
 * Represents one or more resources that were leased from a scope, causing their
 * lifetimes to be extended until `cancel` is invoked on this lease.
 */
abstract class Lease[F[_]] {

  /**
   * Cancels the lease of all resources tracked by this lease.
   *
   * This may run finalizers on some of the resources (depending on the state of their owning scopes).
   * If one or more finalizers fail, the returned action completes with a `Left(t)`, providing the failure.
   */
  def cancel: F[Either[Throwable, Unit]]
}
