package com.artemis.io;

import com.artemis.*;
import com.artemis.annotations.Wire;
import com.artemis.components.SerializationTag;
import com.artemis.managers.GroupManager;
import com.artemis.managers.TagManager;
import com.artemis.utils.Bag;
import com.artemis.utils.ImmutableBag;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Wire(failOnNull = false)
public class KryoEntitySerializer extends Serializer<Entity> {

	private final Bag<Component> components = new Bag<Component>();
	private final ComponentNameComparator comparator = new ComponentNameComparator();
	private final World world;
	private final ReferenceTracker referenceTracker;
	private final DefaultObjectStore defaultValues;
	final KryoEntityPoolFactory factory;

	private GroupManager groupManager;
	private TagManager tagManager;
	private final Collection<String> registeredTags;

	private boolean isSerializingEntity;

	private ComponentMapper<SerializationTag> saveTagMapper;

	SerializationKeyTracker keyTracker;
	ArchetypeMapper archetypeMapper;
	SaveFileFormat serializationState;

	public KryoEntitySerializer (World world, ReferenceTracker referenceTracker) {
		this.world = world;
		this.referenceTracker = referenceTracker;
		defaultValues = new DefaultObjectStore();
		factory = new KryoEntityPoolFactory(world);
		world.inject(this);

		registeredTags = (tagManager != null)
			? tagManager.getRegisteredTags()
			: Collections.<String>emptyList();
	}

	void setUsePrototypes(boolean usePrototypes) {
		defaultValues.setUsePrototypes(usePrototypes);
	}

	void preLoad() {
		keyTracker = new SerializationKeyTracker();
	}

	@Override
	public void write (Kryo kryo, Output output, Entity e) {
		// need to track this in case the components of an entity
		// reference another entity - if so, we only want to record
		// the id
		if (isSerializingEntity) {
			output.writeInt(e.getId());
			return;
		} else {
			isSerializingEntity = true;
		}

		world.getComponentManager().getComponentsFor(e.getId(), components);
		components.sort(comparator);

		// write archetype id
		output.writeInt(e.getCompositionId());

		// write tag
		boolean hasTag = false;
		for (String tag : registeredTags) {
			if (tagManager.getEntity(tag) != e)
				continue;
			output.writeString(tag);
			hasTag = true;
			break;
		}
		if (!hasTag) {
			output.writeString(null);
		}

		// write key tag
		if (saveTagMapper.has(e)) {
			String key = saveTagMapper.get(e).tag;
			output.writeString(key);
		} else {
			output.writeString(null);
		}

		// write group
		if (groupManager == null) {
			output.writeInt(0);
		} else {
			ImmutableBag<String> groups = groupManager.getGroups(e);
			if (groups.size() == 0) {
				output.writeInt(0);
			} else {
				output.writeInt(groups.size());
				for (String group : groups) {
					output.writeString(group);
				}
			}
		}

		// write components
		SaveFileFormat.ComponentIdentifiers identifiers = serializationState.componentIdentifiers;
		Map<Class<? extends Component>, String> typeToName = identifiers.typeToName;

		int count = 0;
		for (int i = 0, s = components.size(); s > i; i++) {
			Component c = components.get(i);
			if (identifiers.isTransient(c.getClass()))
				continue;

			if (defaultValues.hasDefaultValues(c))
				continue;

			count++;
		}
		output.writeInt(count);

		for (int i = 0, s = components.size(); s > i; i++) {
			Component c = components.get(i);
			if (identifiers.isTransient(c.getClass()))
				continue;

			if (defaultValues.hasDefaultValues(c))
				continue;

			String componentIdentifier = typeToName.get(c.getClass());
			output.writeString(componentIdentifier);
			kryo.writeObject(output, c);
		}
		components.clear();

		isSerializingEntity = false;
	}

	@Override
	public Entity read (Kryo kryo, Input input, Class<Entity> aClass) {
		// need to track this in case the components of an entity
		// reference another entity - if so, we only want to read
		// the id
		if (isSerializingEntity) {
			int entityId = input.readInt();
			// creating a temporary entity; this will later be translated
			// to the correct entity
			return FakeEntityFactory.create(world, entityId);
		} else {
			isSerializingEntity = true;
		}

		Entity e = factory.createEntity();

		// read archetype
		int archetype = input.readInt();
		// read tag
		String tag = input.readString();
		if (tag != null) {
			tagManager.register(tag, e);
		}
		// read key tag
		String keyTag = input.readString();
		if (keyTag != null) {
			keyTracker.register(keyTag, e);
			saveTagMapper.create(e).tag = keyTag;
		}
		// read groups
		int groupCount = input.readInt();
		for (int i = 0; i < groupCount; i++) {
			groupManager.add(e, input.readString());
		}
		// read components
		SaveFileFormat.ComponentIdentifiers identifiers = serializationState.componentIdentifiers;
		Map<String, Class<? extends Component>> nameToType = identifiers.nameToType;

		int count = input.readInt();
		// -1 doesn't seem to be a thing
		if (archetype != -1) {
			archetypeMapper.transmute(e, archetype);
		}

		final EntityEdit edit = e.edit();
		for (int i = 0; i < count; i++) {
			String name = input.readString();
			final Class<? extends Component> type = nameToType.get(name);
			// note we use custom serializer because we must use edit.create() for non basic types
			FieldSerializer fieldSerializer = new FieldSerializer(kryo, type) {
				@Override protected Object create (Kryo kryo, Input input, Class type) {
					return edit.create(type);
				}
			};
			Component c = kryo.readObject(input, type, fieldSerializer);
			referenceTracker.addEntityReferencingComponent(c);
		}

		isSerializingEntity = false;

		return e;
	}
}
