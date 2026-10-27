package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.config.ModelConfigData;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/** Shared ranges and precision for the prop sliders and their fine adjustment buttons. */
enum HeldPmxTransformControl {
    ROT_X("rotation_x", -360, 360, 1, 0, c -> c.heldPmxRotX, (c, v) -> c.heldPmxRotX = (float) v),
    ROT_Y("rotation_y", -360, 360, 1, 0, c -> c.heldPmxRotY, (c, v) -> c.heldPmxRotY = (float) v),
    ROT_Z("rotation_z", -360, 360, 1, 0, c -> c.heldPmxRotZ, (c, v) -> c.heldPmxRotZ = (float) v),
    OFFSET_X("offset_x", -20, 20, 0.1f, 0, c -> c.heldPmxOffsetX, (c, v) -> c.heldPmxOffsetX = (float) v),
    OFFSET_Y("offset_y", -20, 20, 0.1f, 0, c -> c.heldPmxOffsetY, (c, v) -> c.heldPmxOffsetY = (float) v),
    OFFSET_Z("offset_z", -20, 20, 0.1f, 0, c -> c.heldPmxOffsetZ, (c, v) -> c.heldPmxOffsetZ = (float) v),
    SCALE("scale", 0.01f, 20, 0.01f, 1, c -> c.heldPmxScale, (c, v) -> c.heldPmxScale = (float) v);

    final String labelKey;
    final float min, max, step, defaultValue;
    private final ToDoubleFunction<ModelConfigData> getter;
    private final ObjDoubleConsumer<ModelConfigData> setter;

    HeldPmxTransformControl(String key, float min, float max, float step, float defaultValue,
                            ToDoubleFunction<ModelConfigData> getter, ObjDoubleConsumer<ModelConfigData> setter) {
        this.labelKey = "gui.mmdskin.model_settings.held_pmx_" + key;
        this.min = min;
        this.max = max;
        this.step = step;
        this.defaultValue = defaultValue;
        this.getter = getter;
        this.setter = setter;
    }

    float get(ModelConfigData config) {
        return (float) getter.applyAsDouble(config);
    }

    void set(ModelConfigData config, float value) {
        float rounded = Math.round(value / step) * step;
        setter.accept(config, Math.max(min, Math.min(max, rounded)));
    }

    static void reset(ModelConfigData config) {
        for (HeldPmxTransformControl control : values()) {
            control.setter.accept(config, control.defaultValue);
        }
    }
}
