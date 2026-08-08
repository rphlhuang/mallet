// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import mallet.NamedProp

/** A reusable, protocol-parameterized set of formal properties.
  *
  * A contract set is the answer to "what must ANY implementation of this
  * protocol satisfy?" -- it is a function from the protocol's bundle to a list
  * of named properties, and it needs NO knowledge of what a particular design
  * does. That is the whole distinction from a role/annotation: a role encodes a
  * design-specific degree of freedom a human must supply, whereas a contract has
  * no such freedom -- it is fully determined by the protocol type. So the
  * "annotation" here is just the presence of the typed bundle, which the
  * compiler already knows; there is no JSON and no lowering step.
  *
  * `properties` runs during module elaboration (via `MalletProperties.contract`),
  * so a set MAY allocate auxiliary monitor hardware -- registers to observe
  * payload stability or track outstanding transactions. Increment 1 allocates
  * none; that machinery arrives in Increment 2.
  *
  * The type parameter `B` is the protocol bundle. Adding AXI4-full or AXI-Stream
  * later means adding another `ContractSet[ThatBundle]`; nothing downstream
  * changes.
  */
trait ContractSet[B] {

  /** Human-readable protocol name, for reports. */
  def name: String

  /** Produce the contract properties for a concrete bundle instance. */
  def properties(bundle: B): Seq[NamedProp]
}
