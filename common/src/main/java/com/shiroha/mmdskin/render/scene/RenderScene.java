package com.shiroha.mmdskin.render.scene;

/** 文件职责：描述一次模型渲染发生的场景上下文。 */
public final class RenderScene {

    public enum SceneType {
        WORLD,
        INVENTORY,
        ITEM,
        GUI,
        OTHER
    }

    public static final RenderScene WORLD = new Builder().sceneType(SceneType.WORLD).build();
    public static final RenderScene INVENTORY = new Builder().sceneType(SceneType.INVENTORY).mirror(true).build();
    public static final RenderScene FIRST_PERSON = new Builder().sceneType(SceneType.WORLD).firstPerson(true).build();
    public static final RenderScene ITEM = new Builder().sceneType(SceneType.ITEM).build();
    public static final RenderScene HELD_ITEM = new Builder().sceneType(SceneType.ITEM).heldItem(true).build();
    public static final RenderScene HELD_ITEM_NO_PHYSICS = new Builder().sceneType(SceneType.ITEM).heldItem(true).physics(false).build();
    public static final RenderScene PAPERDOLL = new Builder().sceneType(SceneType.GUI).mirror(false).firstPerson(false).build();

    private final SceneType sceneType;
    private final boolean firstPerson;
    private final boolean mirror;
    private final boolean heldItem;
    private final boolean physics;

    private RenderScene(Builder builder) {
        this.sceneType = builder.sceneType;
        this.firstPerson = builder.firstPerson;
        this.mirror = builder.mirror;
        this.heldItem = builder.heldItem;
        this.physics = builder.physics;
    }

    public SceneType sceneType() {
        return sceneType;
    }

    public boolean isFirstPerson() {
        return firstPerson;
    }

    public boolean isMirror() {
        return mirror;
    }

    public boolean isWorldScene() {
        return sceneType == SceneType.WORLD;
    }

    public boolean isInventoryScene() {
        return sceneType == SceneType.INVENTORY || sceneType == SceneType.GUI;
    }

    public boolean isPaperDoll() {
        return this == PAPERDOLL;
    }

    public boolean isHeldItem() { return heldItem; }
    public boolean allowsPhysics() { return physics; }

    public static final class Builder {
        private boolean heldItem;
        private boolean physics = true;

        public Builder heldItem(boolean value) { heldItem=value; return this; }
        public Builder physics(boolean value) { physics=value; return this; }
        private SceneType sceneType = SceneType.WORLD;
        private boolean firstPerson;
        private boolean mirror;

        public Builder sceneType(SceneType sceneType) {
            this.sceneType = sceneType;
            return this;
        }

        public Builder firstPerson(boolean firstPerson) {
            this.firstPerson = firstPerson;
            return this;
        }

        public Builder mirror(boolean mirror) {
            this.mirror = mirror;
            return this;
        }

        public RenderScene build() {
            return new RenderScene(this);
        }
    }
}
