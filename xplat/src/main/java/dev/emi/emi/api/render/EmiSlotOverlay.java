package dev.emi.emi.api.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Sets;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.mixin.accessor.HandledScreenAccessor;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiLog;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;

/**
 * Provides a set of methods to render slot overlays.
 */
public class EmiSlotOverlay {
    public static final int OVERLAY_Z = 300;
    public static final Predicate<Slot> ALWAYS_FALSE = new Predicate<>() {
        @Override
        public boolean test(Slot slot) {
            return false;
        }

        @Override
        public @NotNull Predicate<Slot> and(@NotNull Predicate<? super Slot> other) {
            return this;
        }
    };
    public static final Predicate<Slot> ALWAYS_TRUE = new Predicate<>() {
        @Override
        public boolean test(Slot slot) {
            return true;
        }

        @Override
        public @NotNull Predicate<Slot> and(@NotNull Predicate<? super Slot> other) {
            return other::test;
        }
    };

    private static final Map<ScreenHandlerType<?>, Predicate<Slot>> rules = new HashMap<>();
    private static final Map<ScreenHandlerType<?>, List<Consumer<?>>> listeners = new HashMap<>();

    @ApiStatus.Internal
    public static void clear() {
        rules.clear();
        listeners.clear();
    }

    /**
     * Adds a render rule to a specified type of screen handler.
     * Use this to limit the default rendering behavior.
     * To fully disable it, {@link EmiSlotOverlay#disableRenderFor(ScreenHandlerType)} should be used.
     */
    public static void addRenderRule(ScreenHandlerType<?> type, Predicate<Slot> rule) {
        rules.merge(type, rule, Predicate::and);
    }

    /**
     * Completely disable the default rendering behavior for a specific type of screen handler.
     */
    public static void disableRenderFor(ScreenHandlerType<?> type) {
        rules.put(type, ALWAYS_FALSE);
    }

    /**
     * Gets the registered render rule of a specific type of screen handler.
     */
    public static @NotNull Predicate<Slot> getRenderRule(ScreenHandlerType<?> type) {
        Predicate<Slot> rule = rules.get(type);
        if (rule == null) {
            return ALWAYS_TRUE;
        }
        if (rule == ALWAYS_FALSE) {
            return ALWAYS_FALSE;
        }
        return slot -> {
            try {
                return rule.test(slot);
            } catch (Throwable t) {
                EmiLog.error("Overlay Render Rule is throwing in EmiSlotOverlay:", t);
            }
            return false;
        };
    }

    /**
     * Adds a listener to the related type of screen handler.
     * Use this to listen for changes of overlay related data.
     */
    public static <T extends ScreenHandler> void addChangeListener(ScreenHandlerType<T> type, Consumer<HandledScreen<T>> listener) {
        listeners.computeIfAbsent(type, t -> new ArrayList<>()).add(listener);
    }

    @ApiStatus.Internal
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void triggerListeners() {
        HandledScreen<?> hs = EmiApi.getHandledScreen();
        if (hs == null) {
            return;
        }

        ScreenHandlerType<?> type = getHandlerType(hs.getScreenHandler());
        List<Consumer<?>> list = listeners.get(type);
        if (list == null) {
            return;
        }

        try {
            for (Consumer listener : list) {
                listener.accept(hs);
            }
        } catch (Throwable t) {
            EmiLog.error("Listener is throwing in EmiSlotOverlay:", t);
        }
    }

    /**
     * The default render behavior.
     * Applies render rules from {@link EmiSlotOverlay#getRenderRule(ScreenHandlerType)}.
     */
    public static void renderDefault(DrawContext draw, HandledScreen<?> screen) {
        Context cxt = EmiSlotOverlay.getContext();
        if (cxt.isEmpty()) {
            return;
        }

        ScreenHandlerType<?> type = getHandlerType(screen.getScreenHandler());
        cxt.render(draw, screen, EmiSlotOverlay.getRenderRule(type));
    }

    private static @Nullable ScreenHandlerType<?> getHandlerType(ScreenHandler handler) {
        try {
            return handler.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Moves the matrix to the handled screen's position.
     * <br>
     * Since mixin was used to access the location of the handled screen,
     *  this method is provided for convenience, eliminating the need of another mixin.
     * To maintain readability, this method does not push the matrix stack automatically.
     * @return If the matrix stack has been modified.
     */
    public static boolean transformMatrix(DrawContext draw, HandledScreen<?> screen) {
        if (screen instanceof HandledScreenAccessor hsa) {
            EmiDrawContext.wrap(draw).matrices().translate(hsa.getX(), hsa.getY(), 0);
            return true;
        }
        return false;
    }

    /**
     * Gets all the required info to render default slot overlays.
     */
    public static Context getContext() {
        return Context.create();
    }

    /**
     * The default single render behavior. Expects the item to be 16x16 in size.
     * Use {@link EmiSlotOverlay#renderPreTranslated(DrawContext, int, int, int)} if possible,
     *  to reduce unnecessary push/popping of matrix stack.
     * @param color in ARGB
     */
    public static void render(DrawContext draw, int slotX, int slotY, int color) {
        EmiDrawContext context = EmiDrawContext.wrap(draw);
        context.push();
        context.matrices().translate(0, 0, OVERLAY_Z);
        render(context, slotX, slotY, color);
        context.pop();
    }

    /**
     * The default single render behavior. Expects the item to be 16x16 in size.
     * Expects the matrix stack's z value to be already translated by {@link EmiSlotOverlay#OVERLAY_Z}.
     * @param color in ARGB
     */
    public static void renderPreTranslated(DrawContext draw, int slotX, int slotY, int color) {
        render(EmiDrawContext.wrap(draw), slotX, slotY, color);
    }

    private static void render(EmiDrawContext context, int slotX, int slotY, int color) {
        context.fill(slotX - 1, slotY - 1, 18, 18, color);
    }

    public static class Context {
        public static final Function<EmiStack, Integer> EMPTY_FUNC = s -> 0;
        public static final int QUERY_DARKEN_COLOR = 0x77000000;
        public static final int CRAFTING_MODE_HIGHLIGHT_COLOR = 0x7700BBFF;

        private final Predicate<Slot> rule;
        private final Function<EmiStack, Integer> func;
        private final Hint hint;

        private Context(Predicate<Slot> rule, Function<EmiStack, Integer> func, Hint hint) {
            this.rule = rule;
            this.func = func;
            this.hint = hint;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Context create() {
            Hint hint = Hint.NONE;
            Predicate<Slot> rule = ALWAYS_TRUE;
            Function<EmiStack, Integer> func = EMPTY_FUNC;

            if (EmiApi.isSearchHighlightActive()) {
                hint = Hint.QUERY;
                Predicate<EmiStack> query = EmiApi.getSearchQueryPredicate();
                if (query != null) {
                    func = s -> query.test(s) ? 0 : QUERY_DARKEN_COLOR;
                }
            } else if (EmiApi.isInCraftingMode()) {
                hint = Hint.CRAFTING_MODE;
                rule = slot -> !(slot.inventory instanceof PlayerInventory);
                Set<Slot> ignoredSlots = Sets.newHashSet();
                try {
                    HandledScreen<?> hs = EmiApi.getHandledScreen();
                    for (EmiRecipeHandler<?> handler : EmiRecipeFiller.getAllHandlers(hs)) {
                        assert hs != null; // result of getAllHandlers would be empty if it's null.
                        if (handler instanceof StandardRecipeHandler standard) {
                            ignoredSlots.addAll(standard.getInputSources(hs.getScreenHandler()));
                            ignoredSlots.addAll(standard.getCraftingSlots(hs.getScreenHandler()));
                        }
                    }
                } catch (Throwable t) {
                    EmiLog.error("Recipe handler is throwing in EmiSlotOverlay:", t);
                }

                if (!ignoredSlots.isEmpty()) {
                    rule = rule.and(s -> !ignoredSlots.contains(s));
                }

                // don't use the method in the api as it'll cause performance overhead by copying them.
                List<EmiFavorite.Synthetic> syntheticFavorites = EmiFavorites.syntheticFavorites;
                if (!syntheticFavorites.isEmpty()) {
                    Set<EmiStack> synfavs = new HashSet<>(syntheticFavorites.size());
                    for (EmiFavorite.Synthetic fav : syntheticFavorites) {
                        synfavs.addAll(fav.getEmiStacks());
                    }
                    func = s -> synfavs.contains(s) ? CRAFTING_MODE_HIGHLIGHT_COLOR : 0;
                }

            }
            return new Context(rule, func, hint);
        }

        /**
         * @return If it's determined to not render anything.
         */
        public boolean isEmpty() {
            return rule == ALWAYS_FALSE || func == EMPTY_FUNC;
        }

        /**
         * @return If the slot is eligible for overlay.
         *  <br>
         *  This is not affected by {@link EmiSlotOverlay#getRenderRule(ScreenHandlerType)}.
         */
        public boolean test(Slot slot) {
            return rule.test(slot) && slot.isEnabled();
        }

        /**
         * @return The color to be overlaid on the item.
         *  If none, {@code 0} will be returned.
         */
        public int apply(EmiStack stack) {
            return func.apply(stack);
        }

        /**
         * Gets a hint of which type of overlay is being rendered.
         * It's recommended to use the id for comparing,
         *  as it might crash if more overlays are added in the future.
         * <br>
         * This is not affected by {@link Context#isEmpty()}.
         */
        public Hint getHint() {
            return this.hint;
        }

        /**
         * The default render behavior.
         * <br>
         * This is not affected by {@link EmiSlotOverlay#getRenderRule(ScreenHandlerType)}.
         */
        public void render(DrawContext draw, HandledScreen<?> screen) {
            render(draw, screen, null);
        }

        /**
         * The default render behavior.
         * <br>
         * By default, This is not affected by {@link EmiSlotOverlay#getRenderRule(ScreenHandlerType)}.
         */
        public void render(DrawContext draw, HandledScreen<?> screen, @Nullable Predicate<Slot> extraRule) {
            if (isEmpty()) {
                return;
            }

            Predicate<Slot> rule = this::test;
            if (extraRule != null) {
                if (extraRule == ALWAYS_FALSE) {
                    return;
                }
                rule = extraRule.and(rule);
            }

            EmiDrawContext context = EmiDrawContext.wrap(draw);
            context.push();
            context.matrices().translate(0, 0, OVERLAY_Z);
            for (Slot slot : screen.getScreenHandler().slots) {
                if (!rule.test(slot)) {
                    continue;
                }

                EmiStack stack = EmiStack.of(slot.getStack());
                int color = this.apply(stack);
                if (color != 0) {
                    EmiSlotOverlay.render(context, slot.x, slot.y, color);
                }
            }
            context.pop();
        }
    }

    public enum Hint {
        NONE(0),
        QUERY(1),
        CRAFTING_MODE(2);

        public final int id;

        Hint(int id) {
            this.id = id;
        }
    }
}
