package net.porillo;

import co.aikar.commands.MessageType;
import co.aikar.commands.PaperCommandManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import net.porillo.commands.AdminCommands;
import net.porillo.commands.GeneralCommands;
import net.porillo.config.GlobalWarmingConfig;
import net.porillo.config.Lang;
import net.porillo.database.ConnectionManager;
import net.porillo.database.TableManager;
import net.porillo.database.queue.AsyncDBQueue;
import net.porillo.database.tables.WorldTable;
import net.porillo.effect.EffectEngine;
import net.porillo.engine.ClimateEngine;
import net.porillo.listeners.*;
import net.porillo.objects.GPlayer;
import net.porillo.objects.GWorld;
import net.porillo.util.CO2Notifications;
import net.porillo.util.GScoreboard;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.util.Random;

public class GlobalWarming extends JavaPlugin {

	private static GlobalWarming instance; // single plugin instance

	@Getter	private GlobalWarmingConfig conf;
	@Getter	private ConnectionManager connectionManager;
	@Getter	private TableManager tableManager;
	@Getter	private Random random;
	private PaperCommandManager commandManager;
	@Getter	private Gson gson;
	@Getter	private GScoreboard scoreboard;
	@Getter	private CO2Notifications notifications;
	@Getter	private static Economy economy;

	@Override
	public void onEnable() {
		instance = this;

		Lang.init();
		this.random = new Random();
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.conf = new GlobalWarmingConfig();
		this.connectionManager = conf.makeConnectionManager();
		this.tableManager = new TableManager();

		try {
			//Connect to the database:
			// - Refer to setup.sh for setup information
			Connection connection = GlobalWarming.getInstance().getConnectionManager().openConnection();

			//Create the database if it doesn't exist:
			// - Required for the first run
			AsyncDBQueue.getInstance().writeCreateTableQueue(connection);

			//Load any stored records back into memory:
			AsyncDBQueue.getInstance().writeSelectQueue(connection);

			//Confirm that each world has a record:
			// - Required for the first run
			WorldTable worldTable = tableManager.getWorldTable();
			for (World world: Bukkit.getWorlds()) {
				GWorld gWorld = worldTable.getWorld(world.getUID());
				if (gWorld == null) {
					worldTable.insertNewWorld(world.getUID());
				}
			}
		} catch (Exception e) {
			AsyncDBQueue.getInstance().setDemo(true);
			getLogger().severe("MySQL connection not found.");
			getLogger().severe("Data won't persist after restarts!");
			getLogger().severe("Please update config.yml and restart the server.");
		}

		ClimateEngine.getInstance().loadWorldClimateEngines();
		EffectEngine.getInstance();
		this.commandManager = new PaperCommandManager(this);
		this.scoreboard = new GScoreboard();
		this.notifications = new CO2Notifications();
		economy = null;

		registerCommands();
		setupEconomy();

		Bukkit.getPluginManager().registerEvents(new AttributionListener(this), this);
		Bukkit.getPluginManager().registerEvents(new CO2Listener(this), this);
		Bukkit.getPluginManager().registerEvents(new CH4Listener(this), this);
		Bukkit.getPluginManager().registerEvents(new WorldListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

		if (!AsyncDBQueue.getInstance().isDemo()) {
			AsyncDBQueue.getInstance().scheduleAsyncTask(conf.getDatabaseInterval() * 20L);
		}

		Metrics metrics = new Metrics(this);
		metrics.addCustomChart(new Metrics.SimplePie("demo",
				() -> String.valueOf(AsyncDBQueue.getInstance().isDemo())));
	}

	@Override
	public void onDisable() {
		AsyncDBQueue.getInstance().close();
	}

	private void registerCommands() {
		commandManager.enableUnstableAPI("help");
		commandManager.setFormat(MessageType.HELP, ChatColor.GREEN, ChatColor.DARK_GREEN, ChatColor.AQUA);
		commandManager.getCommandContexts().registerIssuerOnlyContext(GPlayer.class, c -> tableManager.getPlayerTable().getPlayers().get(c.getPlayer().getUniqueId()));

		this.commandManager.registerCommand(new AdminCommands());
		this.commandManager.registerCommand(new GeneralCommands());
	}

	/**
	 * Economy (soft-dependency on Vault)
	 * - If a Vault-based economy was not found, disable the bounty system
	 */
	private static void setupEconomy() {
		if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
			RegisteredServiceProvider<Economy> economyProvider = Bukkit.getServicesManager().getRegistration(Economy.class);
			if (economyProvider != null) {
				economy = economyProvider.getProvider();
			}
		}

		if (economy == null) {
			instance.getLogger().warning("Bounty-system [disabled], Vault economy not found");
			for (Permission permission : Bukkit.getPluginManager().getDefaultPermissions(false)) {
				if (permission.getName().startsWith("globalwarming.bounty")) {
					Bukkit.getPluginManager().getPermission(permission.getName())
						.setDefault(PermissionDefault.FALSE);
				}
			}
		} else {
			instance.getLogger().info("Bounty-system [enabled], Vault economy found");
		}
	}

	/**
	 * @return instance of main class
	 */
	public static GlobalWarming getInstance() {
		return instance;
	}
}
