package com.ElytraAutoFix_qwq9648;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.World;


public class ElytraAutoFix implements ClientModInitializer {
    // 状态跟踪变量
    private boolean messageShown = false;
    private boolean hasBottleLastTick = true;
    private int elytraDuraLastTick = 431;
    private int checkCooldown = 0;
    private boolean pauseSent = false; // 标记是否已发送 #pause
    private boolean isFlyingLastTick = false; // 标记玩家是否正在飞行

    // 新增：修复流程状态
    private boolean isFixing = false; // 是否正在修复
    private int bottlesThrown = 0; // 已扔出的经验瓶数量
    private int fixCooldown = 0; // 修复流程冷却时间

    // 新增：地狱世界提示状态
    private boolean netherWarningShown = false; // 是否已显示地狱世界提示

    // 配置参数
    private static final int CHECK_INTERVAL = 20; // 检测间隔（ticks）
    private static final int WARNING_DURABILITY = 50; // 准确耐久值
    private static final int MIN_BOTTLE_COUNT = 20; // 经验瓶最低数量
    private static final int BOTTLES_TO_THROW = 25; // 需要扔出的经验瓶数量

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            checkCooldown--;

            // 主要检测逻辑
            if (checkCooldown <= 0) {
                checkCooldown = CHECK_INTERVAL;
                performChecks(client);
            }

            // 修复流程逻辑
            if (isFixing) {
                performFixProcess(client);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            messageShown = false;
            hasBottleLastTick = true; // 重置状态
            elytraDuraLastTick = 431;
            pauseSent = false; // 重置 #pause 发送状态
            isFlyingLastTick = false; // 重置飞行状态
            isFixing = false; // 重置修复状态
            netherWarningShown = false; // 重置地狱世界提示状态
        });
    }

    private void performChecks(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // 检测是否在地狱世界
        if (!isInNether(client.world)) {
            if (!netherWarningShown) { // 如果未显示过提示
                sendAlert(client, "§c警告：当前不在 下界 ，模组无法运行！\n请前往 下界 ，再使用模组");
                netherWarningShown = true; // 标记为已显示
            }
            return; // 不在地狱世界时停止运行
        } else {
            netherWarningShown = false; // 在地狱世界时重置提示状态
        }

        // 模组加载提示（仅一次）
        if (!messageShown) {
            showWelcomeMessage(client);
            messageShown = true;
        }

        // 检测玩家是否正在使用鞘翅飞行
        boolean isFlyingNow = isPlayerFlying(client.player);
        if (isFlyingNow != isFlyingLastTick) {
            if (isFlyingNow) {
                sendAlert(client, "§a玩家正在飞行，启动检测！");
            } else {
                sendAlert(client, "§c玩家停止飞行，关闭检测！");
            }
        }
        isFlyingLastTick = isFlyingNow;

        // 仅在飞行时执行检测
        if (isFlyingNow) {
            // 经验瓶检测
            boolean hasBottleNow = hasExperienceBottle(client.player.getInventory());
            if (!hasBottleNow && hasBottleLastTick) {
                sendAlert(client, "§c警告：背包中没有经验瓶！");
            }
            hasBottleLastTick = hasBottleNow;

            // 鞘翅耐久检测
            int currentDura = getElytraDurability(client.player.getInventory());
            if (currentDura <= WARNING_DURABILITY && elytraDuraLastTick > WARNING_DURABILITY) {
                sendAlert(client, "§e警告：鞘翅耐久剩余 " + currentDura + "！");
            }
            elytraDuraLastTick = currentDura;

            // 新增功能：当经验瓶≥20且耐久≤50时发送 #pause
            if (getExperienceBottleCount(client.player.getInventory()) >= MIN_BOTTLE_COUNT && currentDura <= WARNING_DURABILITY) {
                if (!pauseSent) { // 如果未发送过 #pause
                    sendChatMessage(client, "#pause");
                    pauseSent = true; // 标记为已发送
                    isFixing = true; // 开始修复流程
                }
            } else {
                pauseSent = false; // 条件不满足时重置状态
            }
        }
    }

    // 检测是否在地狱世界
    private boolean isInNether(World world) {
        return world.getRegistryKey() == World.NETHER;
    }

    // 修复流程逻辑
    private void performFixProcess(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // 步骤1：检测玩家是否落地
        if (!isPlayerOnGround(client.player)) {
            sendAlert(client, "§b等待玩家落地...");
            return;
        }

        // 步骤2：将视角转向脚底
        client.player.setPitch(90f); // 俯视脚底

        // 步骤3：将经验瓶放入快捷栏
        if (!moveBottlesToHotbar(client.player.getInventory())) {
            sendAlert(client, "§c错误：无法将经验瓶放入快捷栏！");
            isFixing = false; // 终止修复流程
            return;
        }

        // 步骤4：模拟右键扔出经验瓶
        if (fixCooldown <= 0) {
            throwExperienceBottle(client);
            fixCooldown = 10; // 冷却时间（10 ticks = 0.5秒）

            if (bottlesThrown >= BOTTLES_TO_THROW) {
                isFixing = false; // 修复流程结束
                bottlesThrown = 0; // 重置计数器

                // 视角看向头顶
                client.player.setPitch(-90f); // 看向头顶
                sendAlert(client, "§a修复完成！");

                // 恢复飞行逻辑
                sendAlert(client, "§a恢复飞行中...");

                // 然后发送聊天信息
                sendChatMessage(client, "#resume");

                // 这里添加模拟按两次空格的逻辑
                simulateDoubleSpacePress(client);
            }
        } else {
            fixCooldown--;
        }
    }

    // 模拟每秒按两次空格，持续 3 秒的函数
    private void simulateDoubleSpacePress(MinecraftClient client) {
        long currentTime = System.currentTimeMillis();
        final long[] lastPressTime = {0}; // 上次按下空格的时间
        // 模拟开始时间
        long duration = 10000; // 模拟持续秒

        // 按空格的线程，每秒两次，持续 3 秒
        new Thread(() -> {
            while (System.currentTimeMillis() - currentTime < duration) {
                if (System.currentTimeMillis() - lastPressTime[0] >= 80) {  // 每按一次空格
                    client.options.jumpKey.setPressed(true);  // 模拟按下空格
                    lastPressTime[0] = System.currentTimeMillis();  // 记录按下时间

                    // 60ms 后抬起空格键
                    new Thread(() -> {
                        try {
                            Thread.sleep(60);  // 持续按下 60ms
                            client.options.jumpKey.setPressed(false);  // 抬起空格
                        } catch (InterruptedException ignored) {
                        }
                    }).start();
                }

                try {
                    Thread.sleep(150);  // 每隔 ms 检查一次，确保一秒按两次
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }


    // 模拟右键扔出经验瓶
    private void throwExperienceBottle(MinecraftClient client) {
        if (client.interactionManager != null && client.player != null) {
            ItemStack mainHandStack = client.player.getMainHandStack();
            if (mainHandStack.getItem() == Items.EXPERIENCE_BOTTLE) {
                int countBefore = mainHandStack.getCount(); // 扔出前的数量
                client.interactionManager.interactItem(
                        client.player,
                        Hand.MAIN_HAND
                );
                int countAfter = client.player.getMainHandStack().getCount(); // 扔出后的数量

                if (countAfter < countBefore) { // 成功扔出
                    bottlesThrown++;
                    sendAlert(client, "§b扔出经验瓶：" + bottlesThrown + "/" + BOTTLES_TO_THROW);
                } else {
                    sendAlert(client, "§c错误：未能成功扔出经验瓶！");
                }
            } else {
                sendAlert(client, "§c错误：玩家未手持经验瓶！");
            }
        } else {
            sendAlert(client, "§c错误：无法扔出经验瓶！");
        }
    }

    // 将经验瓶放入快捷栏
    private boolean moveBottlesToHotbar(PlayerInventory inventory) {
        // 查找快捷栏中是否有经验瓶
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                inventory.selectedSlot = i; // 切换到有经验瓶的位置
                return true;
            }
        }

        // 如果快捷栏没有经验瓶，从背包中取出一组（64 个）经验瓶
        int bottleSlot = findBottleSlot(inventory);
        if (bottleSlot == -1) return false; // 没有经验瓶

        // 查找快捷栏空位
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.selectedSlot = i; // 切换到空位
                inventory.setStack(i, inventory.getStack(bottleSlot).split(64)); // 放入一组经验瓶
                inventory.markDirty(); // 同步服务端
                return true;
            }
        }

        // 如果没有空位，将第 8 个快捷栏的物品与经验瓶交换
        int swapSlot = 8; // 第 8 个快捷栏
        ItemStack temp = inventory.getStack(swapSlot);
        inventory.setStack(swapSlot, inventory.getStack(bottleSlot).split(64)); // 放入一组经验瓶
        inventory.setStack(bottleSlot, temp); // 将原物品放回背包
        inventory.selectedSlot = swapSlot; // 切换到第 8 个快捷栏
        inventory.markDirty(); // 同步服务端
        return true;
    }

    // 检测玩家是否站在实体方块上
    private boolean isPlayerOnGround(PlayerEntity player) {
        return player.isOnGround();
    }

    // 检测玩家是否正在使用鞘翅飞行
    private boolean isPlayerFlying(PlayerEntity player) {
        return player.isFallFlying(); // 鞘翅飞行状态
    }

    // 获取鞘翅当前耐久（精确值）
    private int getElytraDurability(PlayerInventory inventory) {
        ItemStack elytraStack = inventory.getArmorStack(EquipmentSlot.CHEST.getEntitySlotId());
        if (elytraStack.getItem() != Items.ELYTRA) return 431; // 未装备鞘翅时返回最大值

        return elytraStack.getMaxDamage() - elytraStack.getDamage();
    }

    // 检测是否有经验瓶
    private boolean hasExperienceBottle(PlayerInventory inventory) {
        return getExperienceBottleCount(inventory) > 0;
    }

    // 获取经验瓶数量
    private int getExperienceBottleCount(PlayerInventory inventory) {
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                count += inventory.getStack(i).getCount();
            }
        }
        return count;
    }

    // 查找经验瓶的位置
    private int findBottleSlot(PlayerInventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == Items.EXPERIENCE_BOTTLE) {
                return i;
            }
        }
        return -1;
    }

    // 发送聊天消息（普通消息）
    private void sendChatMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.networkHandler.sendChatMessage(message);
        }
    }

    // 消息发送方法
    private void showWelcomeMessage(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§aElytraAutoFix 模组已加载！"),
                    false
            );
        }
    }

    private void sendAlert(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[鞘翅自动修复] ").append(Text.literal(message)),
                    false
            );
        }
    }
}