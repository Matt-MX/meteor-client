/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import com.terraformersmc.modmenu.util.mod.Mod;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class TradeAura extends Module {
    private final Map<UUID, Long> recentlyTradedWith = Collections.synchronizedMap(new HashMap<>());
    private Map<UUID, Integer> cachedTradeIndices = new HashMap<>();
    @Nullable
    public CompletableFuture<MerchantScreen> awaitingMerchantScreen = null;

    public TradeAura() {
        super(Categories.World, "trade-aura", "Trade with all villagers nearby.");
    }

    @Override
    public void onActivate() {
        loadTradeIndices();
    }

    @EventHandler
    private void onClientConnect(GameJoinedEvent event) {
        loadTradeIndices();
    }

    private void loadTradeIndices() {

    }

    @EventHandler
    private void onClientDisconnect(GameLeftEvent event) {
        cachedTradeIndices = null;
        if (awaitingMerchantScreen != null) {
            awaitingMerchantScreen.cancel(true);
            awaitingMerchantScreen = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.interactionManager == null || mc.player == null) {
            return;
        }

        if (awaitingMerchantScreen != null) {
            return;
        }

        double range = mc.player.getEntityInteractionRange();
        Box box = Box.of(mc.player.getPos(), range, range, range);
        List<VillagerEntity> nearby = mc.world.getEntitiesByClass(
            VillagerEntity.class,
            box,
            (villager) -> villager.getPos().isInRange(mc.player.getPos(), range)
        );

        if (nearby.isEmpty()) {
            return;
        }

        VillagerEntity first = null;
        for (final VillagerEntity villager : nearby) {
            if (recentlyTradedWith.containsKey(villager.getUuid())) {
                long millisSinceLastTrade = System.currentTimeMillis() - recentlyTradedWith.get(villager.getUuid());

                if (millisSinceLastTrade < 10_000L) {
                    continue;
                }

                recentlyTradedWith.remove(villager.getUuid());
            }

            first = villager;
            break;
        }

        if (first == null) {
            return;
        }

        if (!cachedTradeIndices.containsKey(first.getUuid())) {
            return;
        }

        int tradeIndex = cachedTradeIndices.get(first.getUuid());

        ActionResult result = mc.interactionManager.interactEntity(mc.player, first, Hand.MAIN_HAND);

        if (!result.isAccepted()) {
            return;
        }

        recentlyTradedWith.put(first.getUuid(), System.currentTimeMillis());

        awaitingMerchantScreen = new CompletableFuture<>();

        awaitingMerchantScreen.thenAccept((merchantScreen) -> {
            MerchantScreenHandler merchantScreenHandler = merchantScreen.getScreenHandler();
            TradeOfferList offers = merchantScreenHandler.getRecipes();

            if (tradeIndex >= offers.size()) {
                return;
            }

            TradeOffer selected = offers.get(tradeIndex);

            Modules.get()
                .get(QuickTrade.class)
                .trade(selected, merchantScreenHandler, tradeIndex);
        });
    }

}
