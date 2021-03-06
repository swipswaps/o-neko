package io.oneko.namespace.event;

import io.oneko.domain.DescribingEntityChange;
import io.oneko.event.EntityChangedEvent;
import io.oneko.namespace.Namespace;
import io.oneko.namespace.WritableNamespace;

import java.util.Collection;

public class DefinedNamespaceSavedEvent extends EntityChangedEvent {

	/**
	 * Use this constructor with the user object prior to actually saving it.
	 */
	public DefinedNamespaceSavedEvent(WritableNamespace namespace) {
		this(namespace, namespace.getDirtyProperties());
	}

	public DefinedNamespaceSavedEvent(Namespace namespace, Collection<String> changedProperties) {
		super(DescribingEntityChange.builder()
				.id(namespace.getId())
				.name(namespace.asKubernetesNameSpace())
				.entityType(DescribingEntityChange.EntityType.Namespace)
				.changeType(DescribingEntityChange.ChangeType.Saved)
				.changedProperties(changedProperties)
				.build());
	}
}
