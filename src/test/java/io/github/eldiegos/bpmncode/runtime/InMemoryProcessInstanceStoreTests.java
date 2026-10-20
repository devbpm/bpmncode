package io.github.eldiegos.bpmncode.runtime;

/**
 * El almacén en memoria cumple el contrato de los almacenes.
 */
class InMemoryProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		return new InMemoryProcessInstanceStore();
	}
}
