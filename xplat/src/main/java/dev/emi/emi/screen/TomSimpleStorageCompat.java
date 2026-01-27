package dev.emi.emi.screen;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Predicate;

public class TomSimpleStorageCompat {
    private static Class<?> tssATClass = null;
    private static Field refreshItemListF = null; // boolean
    private static Class<?> tssHandlerClass = null;
    private static Field slotListF = null; // List<SlotStorage>
    private static Field itemListF = null; // List<StoredItemStack>
    private static Field itemListClientSortedF = null; // List<StoredItemStack>

    private static Field xF = null; // int
    private static Field yF = null; // int
    private static Field storedStackF = null; // StoredItemStack

    private static Field stackF = null; // ItemStack

    private static boolean ok = false;

    private static final WeakHashMap<ScreenHandler, Predicate<EmiStack>> modifiedHandler = new WeakHashMap<>();

    static {
        try {
            // shut up, i'm too lazy to set up mixins
            tssATClass = Class.forName("com.tom.storagemod.gui.AbstractStorageTerminalScreen");
            refreshItemListF = tssATClass.getDeclaredField("refreshItemList");
            refreshItemListF.setAccessible(true);
            tssHandlerClass = Class.forName("com.tom.storagemod.gui.StorageTerminalMenu");
            slotListF = tssHandlerClass.getDeclaredField("storageSlotList");
            itemListF = tssHandlerClass.getDeclaredField("itemList");
            itemListClientSortedF = tssHandlerClass.getDeclaredField("itemListClientSorted");
            slotListF.setAccessible(true);
            itemListF.setAccessible(true);
            itemListClientSortedF.setAccessible(true);
            Class<?> tssSlotClass = Class.forName("com.tom.storagemod.gui.StorageTerminalMenu$SlotStorage");
            xF = tssSlotClass.getDeclaredField("xDisplayPosition");
            yF = tssSlotClass.getDeclaredField("yDisplayPosition");
            storedStackF = tssSlotClass.getDeclaredField("stack");
            xF.setAccessible(true);
            yF.setAccessible(true);
            storedStackF.setAccessible(true);
            Class<?> tssStackClass = Class.forName("com.tom.storagemod.util.StoredItemStack");
            stackF = tssStackClass.getDeclaredField("stack");
            stackF.setAccessible(true);
            ok = true;
        } catch (Throwable t) {
            EmiLog.error("TomSimpleStorageCompat failed to load classes!", t);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isTomsTerminal(Screen screen) {
        return ok && tssATClass.isInstance(screen);
    }

    public static int getTomsStoredSize(ScreenHandler handler) {
        if (!tssHandlerClass.isInstance(handler)) {
            return 0;
        }
        try {
            return ((List<?>) itemListF.get(handler)).size();
        } catch (Throwable ignore) {}
        return 0;
    }

    public static void renderSlotOverlays(
            HandledScreen<?> screen,
            EmiDrawContext context,
            EmiSearch.CompiledQuery query,
            Set<EmiStack> synfavs
    ) {
//        int debugY = -90;
//        context.drawText(Text.literal("Checking OK"), 0, debugY += 10);
        if (!isTomsTerminal(screen)) return;
//        context.drawText(Text.literal("OK, Checking Handler"), 0, debugY += 10);
        ScreenHandler sh = screen.getScreenHandler();
        if (!tssHandlerClass.isInstance(sh)) return;
//        context.drawText(Text.literal("Handler OK, Checking Conditions"), 0, debugY += 10);
        boolean doQuery = query != null;
        boolean doSynfav = !doQuery && BoM.craftingMode && BoM.tree != null;
        if (!doQuery && !doSynfav) return;
//        context.drawText(Text.literal("Conditions OK, Drawing highlights"), 0, debugY += 10);
        // register highlight sorter
        boolean modified = modifiedHandler.containsKey(sh);
        modifiedHandler.put(sh, doQuery ? query::test : synfavs::contains);
        if (!modified) {
            modifySort(screen, sh);
        }
        // draw highlights (query isn't actually highlight, it darkens, so it's reversed)
        context.push();
        context.matrices().translate(-1, -1, 300);
        Predicate<EmiStack> predicate = doQuery ? Predicate.not(query::test) : synfavs::contains;
        int color = doQuery ? 0x77000000 : 0x7700BBFF;
//        int debugCount = 0;
        try {
            for (Object slot : (List<?>) slotListF.get(sh)) {
                Object stored = storedStackF.get(slot);
                if (stored == null) continue;

                EmiStack stack = EmiStack.of((ItemStack) stackF.get(stored));
                if (predicate.test(stack)) {
                    context.fill(xF.getInt(slot), yF.getInt(slot), 18, 18, color);
//                    debugCount++;
                }
            }
        } catch (Throwable t) {
//            context.drawText(Text.literal("Failed to draw highlights"), 0, debugY += 10);
        } finally {
            context.pop();
        }
//        context.drawText(Text.literal("Drew " + debugCount + " highlights"), 0, debugY += 10);
    }

    private static void modifySort(HandledScreen<?> screen, ScreenHandler handler) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> orig = (List<Object>) itemListClientSortedF.get(handler);
            itemListClientSortedF.set(handler, new SynfavSortedList(handler, orig));
            refreshItemListF.setBoolean(screen, true);
        } catch (Throwable ignore) {}
    }

    private static class SynfavSortedList extends ArrayList<Object> {
        private final ScreenHandler key;

        public SynfavSortedList(ScreenHandler key, List<Object> orig) {
            super(orig);
            this.key = key;
        }

        @Override
        public void sort(Comparator<? super Object> c) {
            Predicate<EmiStack> predicate = modifiedHandler.get(this.key);
            if (predicate == null) {
                // impossible, but just to be sure
                super.sort(c);
                return;
            }
            Map<Object, Integer> cache = new IdentityHashMap<>(this.size());
            super.sort(Comparator.comparing(o -> cache.computeIfAbsent(o, k -> {
                try {
                    return predicate.test(EmiStack.of((ItemStack) stackF.get(k))) ? 0 : 1;
                } catch (Throwable ignore) {}
                return 1;
            })).thenComparing(c));
        }
    }
}
