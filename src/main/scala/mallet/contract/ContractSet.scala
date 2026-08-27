// SPDX-License-Identifier: Apache-2.0
package mallet.contract

import mallet.NamedProp

trait ContractSet[B] {

  // human-readable protocol name, for reports
  def name: String

  // produce the contract properties for a concrete bundle instance
  def properties(bundle: B): Seq[NamedProp]
}
