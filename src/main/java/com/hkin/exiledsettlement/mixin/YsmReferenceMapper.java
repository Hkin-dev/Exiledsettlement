package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.compat.YsmCompatibility;
import net.okitsu.ysmmapping.api.mixin.YsmRuntimeMappings;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;

import java.util.Objects;

/** Lets Mapping API rewrite only the declared YSM aliases at runtime. */
public final class YsmReferenceMapper implements IReferenceMapper {
    private final IReferenceMapper delegate;

    public YsmReferenceMapper(MixinEnvironment environment, IReferenceMapper delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public boolean isDefault() { return false; }
    @Override public String getResourceName() { return "YSM Mapping API runtime mappings"; }
    @Override public String getStatus() { return "Using YSM Mapping API runtime mappings"; }
    @Override public String getContext() { return delegate.getContext(); }
    @Override public void setContext(String context) { delegate.setContext(context); }

    @Override
    public String remap(String className, String reference) {
        if (!YsmCompatibility.available()) return delegate.remap(className, reference);
        String mapped = YsmRuntimeMappings.mapReference(reference);
        if (!mapped.equals(reference)) return mapped;
        mapped = YsmRuntimeMappings.mapClass(reference);
        return mapped.equals(reference) ? delegate.remap(className, reference) : mapped;
    }

    @Override
    public String remapWithContext(String context, String className, String reference) {
        if (!YsmCompatibility.available()) return delegate.remapWithContext(context, className, reference);
        String mapped = YsmRuntimeMappings.mapReference(reference);
        if (!mapped.equals(reference)) return mapped;
        mapped = YsmRuntimeMappings.mapClass(reference);
        return mapped.equals(reference)
                ? delegate.remapWithContext(context, className, reference) : mapped;
    }
}
