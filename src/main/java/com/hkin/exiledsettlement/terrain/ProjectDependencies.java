package com.hkin.exiledsettlement.terrain;

import java.util.*;

/** Mutable dependency membership with immediate derived-index invalidation. */
final class ProjectDependencies extends AbstractSet<UUID> {
    private final Set<UUID> values=new LinkedHashSet<>();
    Runnable changed=()->{};
    @Override public int size(){return values.size();}
    @Override public boolean contains(Object value){return values.contains(value);}
    @Override public boolean add(UUID value){if(!values.add(value))return false;changed.run();return true;}
    @Override public boolean remove(Object value){if(!values.remove(value))return false;changed.run();return true;}
    @Override public void clear(){if(!values.isEmpty()){values.clear();changed.run();}}
    @Override public Iterator<UUID> iterator(){
        var iterator=values.iterator();return new Iterator<>(){
            public boolean hasNext(){return iterator.hasNext();}
            public UUID next(){return iterator.next();}
            public void remove(){iterator.remove();changed.run();}
        };
    }
}
