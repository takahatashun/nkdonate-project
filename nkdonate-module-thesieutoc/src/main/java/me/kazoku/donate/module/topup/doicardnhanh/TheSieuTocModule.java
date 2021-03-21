package me.kazoku.donate.module.topup.doicardnhanh;

import com.doicardnhanh.api.CardPrice;
import com.doicardnhanh.api.CardType;
import com.doicardnhanh.api.TheSieuTocAPI;
import com.google.gson.JsonObject;
import me.kazoku.artxe.converter.time.prototype.TickConverter;
import me.kazoku.donate.NKDonatePlugin;
import me.kazoku.donate.external.api.NKDonateAPI;
import me.kazoku.donate.internal.handler.RewardsProfile;
import me.kazoku.donate.internal.util.file.FileUtils;
import me.kazoku.donate.internal.util.json.JsonParser;
import me.kazoku.donate.modular.topup.Response;
import me.kazoku.donate.modular.topup.TopupModule;
import me.kazoku.donate.modular.topup.object.Card;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.Configuration;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;

public class TheSieuTocModule extends TopupModule {

  private static final RewardsProfile rewards = new RewardsProfile();
  private static final List<Card.Type> CARD_TYPES = new ArrayList<>();
  private static final List<Card.Price> CARD_PRICES = new ArrayList<>();
  private File configFile;
  private TheSieuTocAPI theSieuTocApi;
  private long period = 1200L;

  @Override
  public boolean onPreStartup() {
    configFile = new File(getDataFolder(), "config.yml");
    saveDefaults();
    return loadConfig();
  }

  @Override
  public void onStartup() {
    NKDonatePlugin.getInstance().getDebugLogger().debug("[TST] Running checking task...");
    NKDonateAPI.runAsyncTimerTask(NKDonatePlugin.getInstance().getQueue()::checkAll, 0, period);
    NKDonatePlugin.getInstance().getDebugLogger().debug(() -> "[TST] Period: " + period);
  }

  private void saveDefaults() {
    final InputStream is = getClassLoader().getResourceAsStream("config.yml");
    if (Objects.nonNull(is)) {
      if (configFile.exists()) {
        Configuration config = getConfig();
        config.setDefaults(YamlConfiguration.loadConfiguration(is));
        config.options().copyDefaults(true);
      } else {
        FileUtils.toFile(is, configFile, getModuleManager().getLogger());
      }
    }
  }

  private boolean loadConfig() {
    final Configuration config = getConfig();
    final String apiKey = config.getString("APIKey", "");
    final String apiSecret = config.getString("APISecret", "");
    final String rewardProfile = config.getString("RewardProfile", "");

    if (apiKey.isEmpty() || apiSecret.isEmpty()) {
      getModuleManager().getLogger().log(Level.WARNING, "Missing API information, disabling...");
      return false;
    }

    theSieuTocApi = new TheSieuTocAPI(apiKey, apiSecret);

    if (!rewardProfile.isEmpty()) rewards.load(config.getConfigurationSection("Rewards." + rewardProfile));

    period = TickConverter.convertToTick(config.get("Period", "1200tick")).getValue().longValue();

    CARD_TYPES.clear();
    final ConfigurationSection typeSection = config.getConfigurationSection("Card.Type");
    Arrays.stream(CardType.values())
        .forEach(type -> Optional.ofNullable(typeSection.getString(type.getValue()))
            .map(type::toGenericType)
            .ifPresent(CARD_TYPES::add));

    CARD_PRICES.clear();
    final ConfigurationSection priceSection = config.getConfigurationSection("Card.Price");
    Arrays.stream(CardPrice.values())
        .forEach(price -> Optional.ofNullable(priceSection.getString(price.getValue()))
            .map(price::toGenericPrice)
            .ifPresent(CARD_PRICES::add));
    return true;
  }

  @NotNull
  @Override
  public Response sendCard(Card card) {
    JsonObject json = JsonParser.parseString(theSieuTocApi.createTransaction(
        card.getType().getValue(),
        card.getPrice().getValue(),
        card.getSerial(),
        card.getPin()
    )).getAsJsonObject();
    boolean success = json.get("status").getAsString().equals("00");
    if (success) card.updateId(json.get("transaction_id")::getAsString);
    return new Response(success, json.get("msg").getAsString());
  }

  @Override
  public void checkCard(Card card) {
    JsonObject json = JsonParser.parseString(theSieuTocApi.checkTransaction(card.getId())).getAsJsonObject();
    Card.Status status;
    switch (json.get("status").getAsString()) {
      case "00":
        status = Card.Status.SUCCESS;
        break;
      case "-9":
        status = Card.Status.AWAITING;
        break;
      default:
        status = Card.Status.FAILED;
        break;
    }
    card.updateStatus(status, json.get("msg").getAsString());
  }

  @Override
  public List<Card.Type> getCardTypes() {
    return CARD_TYPES;
  }

  @Override
  public List<Card.Price> getCardPrices() {
    return CARD_PRICES;
  }

  @Override
  public RewardsProfile getRewards() {
    return rewards;
  }
}