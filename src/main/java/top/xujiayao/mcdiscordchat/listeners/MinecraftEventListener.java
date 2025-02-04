package top.xujiayao.mcdiscordchat.listeners;

import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import top.xujiayao.mcdiscordchat.Main;
import top.xujiayao.mcdiscordchat.events.CommandExecutionCallback;
import top.xujiayao.mcdiscordchat.events.PlayerAdvancementCallback;
import top.xujiayao.mcdiscordchat.events.PlayerDeathCallback;
import top.xujiayao.mcdiscordchat.events.PlayerJoinCallback;
import top.xujiayao.mcdiscordchat.events.PlayerLeaveCallback;
import top.xujiayao.mcdiscordchat.events.ServerChatCallback;
import top.xujiayao.mcdiscordchat.utils.MarkdownParser;
import top.xujiayao.mcdiscordchat.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static top.xujiayao.mcdiscordchat.Main.config;
import static top.xujiayao.mcdiscordchat.Main.textChannel;

/**
 * @author Xujiayao
 */
public class MinecraftEventListener {

	long lastTime = System.currentTimeMillis();
	String lastPlayer = "";

	public void init() {
		ServerChatCallback.EVENT.register((playerEntity, rawMessage, message) -> {
			if (!Main.stop) {
				try {
					if (StringUtils.countMatches(rawMessage, ":") >= 2) {
						String[] emoteNames = StringUtils.substringsBetween(rawMessage, ":", ":");
						for (String emoteName : emoteNames) {
							List<Emote> emotes = Main.jda.getEmotesByName(emoteName, true);
							if (!emotes.isEmpty()) {
								rawMessage = RegExUtils.replaceFirst(rawMessage, ":" + emoteName + ":", "<:" + emoteName + ":" + emotes.get(0).getId() + ">");
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					textChannel.sendMessage("```\n" + ExceptionUtils.getStackTrace(e) + "\n```").queue();
				}

				Pair<String, String> convertedPair = Utils.convertMentionsFromNames(rawMessage);

				if (config.generic.bannedMinecraft.contains(playerEntity.getEntityName())) {
					return Optional.empty();
				}

				JSONObject body = new JSONObject();
				body.put("username", (config.generic.multiServer ? "[" + config.multiServer.serverDisplayName + "] " + playerEntity.getEntityName() : playerEntity.getEntityName()));
				body.put("avatar_url", config.generic.avatarAPI.replace("%player%", (config.generic.useUUIDInsteadNickname ? playerEntity.getUuid().toString() : playerEntity.getEntityName())));

				JSONObject allowedMentions = new JSONObject();
				allowedMentions.put("parse", new String[]{"users", "roles"});

				body.put("allowed_mentions", allowedMentions);
				body.put("content", convertedPair.getLeft().replace("_", "\\_"));

				try {
					Unirest.post(config.generic.webhookURL).header("Content-Type", "application/json").body(body).asJsonAsync();
				} catch (Exception e) {
					e.printStackTrace();
					textChannel.sendMessage("```\n" + ExceptionUtils.getStackTrace(e) + "\n```").queue();
				}

				if (config.generic.modifyChatMessages) {
					String jsonString = Text.Serializer.toJson(message);
					JSONObject newComponent = new JSONObject(jsonString);

					if (convertedPair.getRight().startsWith("!!")) {
						newComponent.getJSONArray("with").put(1, convertedPair.getRight());
					} else {
						newComponent.getJSONArray("with").put(1, MarkdownParser.parseMarkdown(convertedPair.getRight()));
					}

					Text finalText = Text.Serializer.fromJson(newComponent.toString());

					return Optional.ofNullable(finalText);
				}
			}

			return Optional.empty();
		});

		CommandExecutionCallback.EVENT.register((command, source) -> {
			if (!Main.stop && config.generic.broadcastCommandExecution) {
				try {
					String temp = command;

					if (temp.contains(" ")) {
						temp = temp.substring(0, temp.indexOf(" "));
					}

					if (config.generic.excludedCommands.contains(temp)) {
						return;
					}

					if (config.generic.bannedMinecraft.contains(source.getPlayer().getEntityName())) {
						return;
					}

					if (source.getPlayer().getEntityName().equals(lastPlayer)
							&& (System.currentTimeMillis() - lastTime) < 100) {
						return;
					}

					lastTime = System.currentTimeMillis();
					lastPlayer = source.getPlayer().getEntityName();

					JSONObject body = new JSONObject();
					body.put("username", (config.generic.multiServer ? "[" + config.multiServer.serverDisplayName + "] " + source.getPlayer().getEntityName() : source.getPlayer().getEntityName()));
					body.put("avatar_url", config.generic.avatarAPI.replace("%player%", (config.generic.useUUIDInsteadNickname ? source.getPlayer().getUuid().toString() : source.getPlayer().getEntityName())));

					JSONObject allowedMentions = new JSONObject();
					allowedMentions.put("parse", new String[]{"users", "roles"});

					body.put("allowed_mentions", allowedMentions);
					body.put("content", command.replace("_", "\\_"));

					Unirest.post(config.generic.webhookURL).header("Content-Type", "application/json").body(body).asJsonAsync();
				} catch (Exception e) {
					e.printStackTrace();
					textChannel.sendMessage("```\n" + ExceptionUtils.getStackTrace(e) + "\n```").queue();
				}

				try {
					List<ServerPlayerEntity> list = new ArrayList<>(Objects.requireNonNull(Utils.getServer()).getPlayerManager().getPlayerList());
					list.remove(source.getPlayer());
					list.forEach(
							serverPlayerEntity -> {
								try {
									serverPlayerEntity.sendMessage(new LiteralText("<").append(source.getPlayer().getEntityName()).append("> ").append(command), false);
								} catch (Exception e) {
									e.printStackTrace();
									textChannel.sendMessage("```\n" + ExceptionUtils.getStackTrace(e) + "\n```").queue();
								}
							});
				} catch (Exception e) {
					e.printStackTrace();
					textChannel.sendMessage("```\n" + ExceptionUtils.getStackTrace(e) + "\n```").queue();
				}
			}
		});

		PlayerAdvancementCallback.EVENT.register((playerEntity, advancement) -> {
			if (config.generic.announceAdvancements && advancement.getDisplay() != null
					&& advancement.getDisplay().shouldAnnounceToChat()
					&& playerEntity.getAdvancementTracker().getProgress(advancement).isDone() && !Main.stop) {
				switch (advancement.getDisplay().getFrame()) {
					case GOAL -> textChannel.sendMessage(Main.texts.advancementGoal()
							.replace("%playername%", MarkdownSanitizer.escape(playerEntity.getEntityName()))
							.replace("%advancement%", advancement.getDisplay().getTitle().getString())).queue();
					case TASK -> textChannel.sendMessage(Main.texts.advancementTask()
							.replace("%playername%", MarkdownSanitizer.escape(playerEntity.getEntityName()))
							.replace("%advancement%", advancement.getDisplay().getTitle().getString())).queue();
					case CHALLENGE -> textChannel.sendMessage(Main.texts.advancementChallenge()
							.replace("%playername%", MarkdownSanitizer.escape(playerEntity.getEntityName()))
							.replace("%advancement%", advancement.getDisplay().getTitle().getString())).queue();
				}
			}
		});

		PlayerDeathCallback.EVENT.register((playerEntity, damageSource) -> {
			if (config.generic.announceDeaths && !Main.stop) {
				textChannel.sendMessage(Main.texts.deathMessage()
						.replace("%deathmessage%", MarkdownSanitizer.escape(damageSource.getDeathMessage(playerEntity).getString()))
						.replace("%playername%", MarkdownSanitizer.escape(playerEntity.getEntityName()))).queue();
			}
		});

		PlayerJoinCallback.EVENT.register((connection, playerEntity) -> {
			if (config.generic.announcePlayers && !Main.stop) {
				textChannel.sendMessage(Main.texts.joinServer().replace("%playername%",
						MarkdownSanitizer.escape(playerEntity.getEntityName()))).queue();
			}
		});

		PlayerLeaveCallback.EVENT.register(playerEntity -> {
			if (config.generic.announcePlayers && !Main.stop) {
				textChannel.sendMessage(Main.texts.leftServer().replace("%playername%",
						MarkdownSanitizer.escape(playerEntity.getEntityName()))).queue();
			}
		});
	}
}
