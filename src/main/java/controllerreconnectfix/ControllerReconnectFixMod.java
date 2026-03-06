package controllerreconnectfix;

import basemod.BaseMod;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerManager;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.codedisaster.steamworks.SteamControllerHandle;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.controller.CInputHelper;
import com.megacrit.cardcrawl.helpers.controller.CInputListener;
import com.megacrit.cardcrawl.helpers.steamInput.SteamInputHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

@SpireInitializer
public class ControllerReconnectFixMod implements PostUpdateSubscriber {
    private static final Logger logger = LogManager.getLogger(ControllerReconnectFixMod.class.getName());

    private static final long DIRECT_SCAN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long STEAM_SCAN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long MANAGER_REFRESH_COOLDOWN_NS = TimeUnit.SECONDS.toNanos(2);

    private static long lastDirectScanNs;
    private static long lastSteamScanNs;
    private static long lastManagerRefreshNs;

    private static SteamControllerHandle lastSteamHandle;
    private static boolean lastSteamConnected;

    public static void initialize() {
        BaseMod.subscribe(new ControllerReconnectFixMod());
        logger.info("[ControllerReconnectFix] initialized");
    }

    @Override
    public void receivePostUpdate() {
        long now = System.nanoTime();

        if (now - lastDirectScanNs >= DIRECT_SCAN_INTERVAL_NS) {
            lastDirectScanNs = now;
            tickDirectInput(now);
        }

        if (now - lastSteamScanNs >= STEAM_SCAN_INTERVAL_NS) {
            lastSteamScanNs = now;
            tickSteamInput();
        }
    }

    private static void tickDirectInput(long now) {
        if (!Settings.CONTROLLER_ENABLED || Gdx.app == null) {
            return;
        }

        Array<Controller> controllers;
        try {
            controllers = Controllers.getControllers();
        } catch (Throwable ignored) {
            return;
        }
        if (controllers == null) {
            return;
        }

        CInputHelper.controllers = controllers;
        Controller current = CInputHelper.controller;
        boolean currentPresent = current != null && containsController(controllers, current);

        if (controllers.size > 0 && (!currentPresent || CInputHelper.listener == null)) {
            bindController(controllers.first());
            return;
        }
        
        if (shouldRefreshManager(controllers, currentPresent)
                && now - lastManagerRefreshNs >= MANAGER_REFRESH_COOLDOWN_NS) {
            if (refreshControllerManager()) {
                lastManagerRefreshNs = now;
                Array<Controller> refreshed = Controllers.getControllers();
                CInputHelper.controllers = refreshed;
                if (refreshed != null && refreshed.size > 0) {
                    Controller refreshedCurrent = CInputHelper.controller;
                    boolean refreshedPresent = refreshedCurrent != null && containsController(refreshed, refreshedCurrent);
                    if (!refreshedPresent || CInputHelper.listener == null) {
                        bindController(refreshed.first());
                    }
                }
            }
        }
    }

    private static void tickSteamInput() {
        if (!SteamInputHelper.alive
                || SteamInputHelper.controller == null
                || SteamInputHelper.controllerHandles == null) {
            return;
        }

        try {
            int connected = SteamInputHelper.controller.getConnectedControllers(SteamInputHelper.controllerHandles);
            SteamInputHelper.numControllers = connected;

            if (connected <= 0) {
                lastSteamHandle = null;
                lastSteamConnected = false;
                return;
            }

            SteamControllerHandle handle = SteamInputHelper.controllerHandles[0];
            if (handle == null) {
                return;
            }

            boolean handleChanged = !sameSteamHandle(lastSteamHandle, handle);
            boolean needsReinit = handleChanged || !sameSteamHandle(SteamInputHelper.handle, handle);

            if (needsReinit) {
                SteamInputHelper.initActions(handle);
                if (!lastSteamConnected || handleChanged) {
                    Settings.isControllerMode = true;
                    Settings.isTouchScreen = false;
                    logger.info("[ControllerReconnectFix] SteamInput handle rebound: {}", handle);
                }
            }

            lastSteamHandle = handle;
            lastSteamConnected = true;
        } catch (Throwable ignored) {
        }
    }

    private static void bindController(Controller controller) {
        if (controller == null) {
            return;
        }

        try {
            if (CInputHelper.listener == null) {
                CInputHelper.listener = new CInputListener();
            }
            CInputHelper.setController(controller);
            applyDirectInputPresentation(controller);
            logger.info("[ControllerReconnectFix] DirectInput controller rebound: {}", safeName(controller));
        } catch (Throwable t) {
            logger.warn("[ControllerReconnectFix] Failed to bind DirectInput controller", t);
        }
    }

    private static boolean containsController(Array<Controller> list, Controller target) {
        if (list == null || target == null) {
            return false;
        }
        for (Controller item : list) {
            if (item == target) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean shouldRefreshManager(Array<Controller> controllers, boolean currentPresent) {
        if (!Settings.CONTROLLER_ENABLED) {
            return false;
        }
        if (controllers == null || controllers.size == 0) {
            return true;
        }
        if (CInputHelper.controller == null) {
            return true;
        }
        if (!currentPresent) {
            return true;
        }
        if (CInputHelper.listener == null) {
            return true;
        }
        return false;
    }
    
    private static boolean refreshControllerManager() {
        try {
            Field managersField = Controllers.class.getDeclaredField("managers");
            managersField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            ObjectMap<Application, ControllerManager> managers =
                    (ObjectMap<Application, ControllerManager>) managersField.get(null);
            if (managers == null || Gdx.app == null) {
                return false;
            }
            
            ControllerManager oldManager = managers.remove(Gdx.app);
            if (oldManager != null) {
                oldManager.clearListeners();
            }
            
            Controllers.getControllers();
            logger.info("[ControllerReconnectFix] Refreshed controller manager");
            return true;
        } catch (Throwable t) {
            logger.warn("[ControllerReconnectFix] Failed to refresh controller manager", t);
            return false;
        }
    }

    private static String safeName(Controller controller) {
        try {
            return controller.getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void applyDirectInputPresentation(Controller controller) {
        CInputHelper.ControllerModel model = detectDirectInputModel(controller);
        CInputHelper.model = model;
        ImageMaster.loadControllerImages(model);
    }

    private static CInputHelper.ControllerModel detectDirectInputModel(Controller controller) {
        String name = safeName(controller);
        if (name.contains("360")) {
            return CInputHelper.ControllerModel.XBOX_360;
        }
        if (name.contains("Xbox One")) {
            return CInputHelper.ControllerModel.XBOX_ONE;
        }
        return CInputHelper.ControllerModel.XBOX_360;
    }

    private static boolean sameSteamHandle(SteamControllerHandle left, SteamControllerHandle right) {
        return left == right || (left != null && left.equals(right));
    }
}
