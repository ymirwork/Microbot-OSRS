package net.runelite.client.plugins.microbot.accountselector;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.world.Rs2WorldUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced AutoLoginScript with intelligent world selection and login watchdog.
 * Provides robust login handling with retry logic and extended sleep states.
 */
@Slf4j
public class AutoLoginScript extends Script {
    
    // Login state management
    private enum LoginState {
        WAITING_FOR_LOGIN_SCREEN,
        ATTEMPTING_LOGIN,
        LOGIN_EXTENDED_SLEEP,
        ERROR
    }
    
    private LoginState loginState = LoginState.WAITING_FOR_LOGIN_SCREEN;
    private int retryCount = 0;
    private Instant loginWatchdogStartTime = null;
    private Instant extendedSleepStartTime = null;
    private Instant lastLoginAttemptTime = null;
    private long lastExtendedSleepLoggedMinute = -1;


    public boolean run(AutoLoginConfig autoLoginConfig) {
        log.info("Starting AutoLogin script with world selection");
        
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (BreakHandlerScript.isBreakActive() || BreakHandlerScript.isMicroBreakActive()) return;

                processAutoLoginStateMachine(autoLoginConfig);

            } catch (Exception ex) {
                log.error("Error in auto login script", ex);
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }
    
    /**
     * Main state machine for auto login processing.
     */
    private void processAutoLoginStateMachine(AutoLoginConfig config) {
        switch (loginState) {
            case WAITING_FOR_LOGIN_SCREEN:
                handleWaitingForLoginScreenState(config);
                break;
            case ATTEMPTING_LOGIN:
                handleAttemptingLoginState(config);
                break;
            case LOGIN_EXTENDED_SLEEP:
                handleLoginExtendedSleepState(config);
                break;
            case ERROR:
                log.error("Auto login script in error state, resetting disable auto login");
                Microbot.stopPlugin(AutoLoginPlugin.class);
        }
    }
    
    /**
     * State: WAITING_FOR_LOGIN_SCREEN
     * Monitoring for login screen to appear.
     */
    private void handleWaitingForLoginScreenState(AutoLoginConfig config) {
        if (Microbot.isLoggedIn()) {
            // reset state if already logged in
            resetLoginState();
            return;
        }
      
       
        if (Microbot.getClient()!=null && Microbot.getClient().getGameState() == GameState.LOGIN_SCREEN) {
            log.info("Login screen detected, initiating login");
            initiateLogin(config);
            transitionToState(LoginState.ATTEMPTING_LOGIN);
        }
    }
    
    /**
     * State: ATTEMPTING_LOGIN
     * Currently attempting to log in with watchdog monitoring.
     */
    private void handleAttemptingLoginState(AutoLoginConfig config) {
        if (Microbot.isLoggedIn()) {
            log.info("Login successful");
            resetLoginState();
            transitionToState(LoginState.WAITING_FOR_LOGIN_SCREEN);
            return;
        }
        
        // check login watchdog timeout if enabled
        if (config.enableLoginWatchdog() && loginWatchdogStartTime != null) {
            long watchdogTime = Duration.between(loginWatchdogStartTime, Instant.now()).toMinutes();
            if (watchdogTime >= config.loginWatchdogTimeout()) {
                log.warn("Login watchdog timeout reached after {} minutes, entering extended sleep", watchdogTime);
                extendedSleepStartTime = Instant.now();
                transitionToState(LoginState.LOGIN_EXTENDED_SLEEP);
                return;
            }
        }
        
        // check if enough time has passed for retry
        if (lastLoginAttemptTime != null) {
            long timeSinceLastAttempt = Duration.between(lastLoginAttemptTime, Instant.now()).toSeconds();
            if (timeSinceLastAttempt < config.loginRetryDelay()) {
                return; // wait for retry delay
            }
        }
        
        // check retry limit
        if (retryCount >= config.maxLoginRetries()) {
            if (config.enableLoginWatchdog()) {
                log.warn("Max login retries reached, entering extended sleep");
                extendedSleepStartTime = Instant.now();
                transitionToState(LoginState.LOGIN_EXTENDED_SLEEP);
            } else {
                log.warn("Max login retries reached, resetting retry count");
                retryCount = 0;
            }
            return;
        }
        
        // check if still on login screen
        if (Microbot.getClient()!=null && Microbot.getClient().getGameState() == GameState.LOGIN_SCREEN) {
            log.info("Retrying login attempt {} of {}", retryCount + 1, config.maxLoginRetries());
            int currentLoginIndex = Microbot.getClient().getLoginIndex();
            if (Microbot.getClient().getLoginIndex() == 3 || Microbot.getClient().getLoginIndex() == 24) { // you were disconnected from the server.
                log.info("Detected disconnection screen");
                //should be handled in Login class, on next login call
            }
            if (currentLoginIndex == 4 || currentLoginIndex == 3) { // we are in the auth screen and cannot login
                // 3 mean wrong authtifaction
                log.error("Authentication failed, please check credentials");
                transitionToState(LoginState.ERROR);
                return;                
            }
            if (currentLoginIndex == 34) { // we are not a member and cannot login
                log.error("Account is not a member, cannot login to members world");
                transitionToState(LoginState.ERROR);
                return;
            }
            // we have to find out  other indexes that mean we cannot login
            initiateLogin(config);
        } else {
          
           
            // not on login screen anymore, return to waiting
            resetLoginState();
            transitionToState(LoginState.WAITING_FOR_LOGIN_SCREEN);
        }
    }
    
    /**
     * State: LOGIN_EXTENDED_SLEEP
     * Extended sleep state after login failures.
     */
    private void handleLoginExtendedSleepState(AutoLoginConfig config) {
        if (Microbot.isLoggedIn()) {
            log.info("Login successful during extended sleep");
            resetLoginState();
            transitionToState(LoginState.WAITING_FOR_LOGIN_SCREEN);
            return;
        }
        
        if (extendedSleepStartTime == null) {
            extendedSleepStartTime = Instant.now();
            log.info("Extended sleep started for {} minutes", config.extendedSleepDuration());
            return;
        }
        
        // check if extended sleep period is complete
        long sleepTime = Duration.between(extendedSleepStartTime, Instant.now()).toMinutes();
        if (sleepTime >= config.extendedSleepDuration()) {
            log.info("Extended sleep period completed after {} minutes, resuming login attempts", sleepTime);
            resetLoginState();
            transitionToState(LoginState.WAITING_FOR_LOGIN_SCREEN);
        } else {
            // show progress occasionally
            if (sleepTime > 0 && sleepTime % 5 == 0 && sleepTime != lastExtendedSleepLoggedMinute) {
                long remaining = config.extendedSleepDuration() - sleepTime;
                log.debug("Extended sleep in progress - {} minutes remaining", remaining);
                lastExtendedSleepLoggedMinute = sleepTime;
            }
        }
    }
    
    /**
     * Initiates intelligent login based on configuration.
     */
    private void initiateLogin(AutoLoginConfig config) {
        try {
            // start login watchdog if enabled and not already started
            if (config.enableLoginWatchdog() && loginWatchdogStartTime == null) {
                loginWatchdogStartTime = Instant.now();
                log.info("Login watchdog started for {} minutes", config.loginWatchdogTimeout());
            }
            
            int targetWorld = -1;
            
            boolean membersOnly = config.membersOnly();
            
            // use world selection mode if no preferred world or preferred world not accessible
            if (targetWorld == -1) {
                switch (config.worldSelectionMode()) {
                    case CURRENT_PREFERRED_WORLD:
                        boolean isAccessible = Rs2WorldUtil.canAccessWorld(config.world());
                        
                        if (isAccessible) {
                            targetWorld = config.world();
                            log.info("Using preferred world: {}", targetWorld);
                        } else {
                            ConfigProfile activeProfile = Login.activeProfile;
                            boolean isMemberFromProfile = activeProfile != null && activeProfile.isMember();
                            boolean isLocalPlayerAvailable = Microbot.getClient()!=null && Microbot.getClient().getLocalPlayer() != null;
                            boolean isMemberFromClient = Microbot.getClient()!=null && Microbot.getClient().getLocalPlayer() != null ? Rs2Player.isMember() : false;
                            log.error("Preferred world {} is not accessible,\n\t ->check if we have member access set in profile(current value {}), or when logged in, have we member access ? (LocalPlayer? {}, isMember? {})", 
                            config.usePreferredWorld(), isMemberFromProfile, isLocalPlayerAvailable, isMemberFromClient);                        
                        }
                        // no specific world selection - use default login
                        break;
                        
                    case RANDOM_WORLD:
                        targetWorld = Rs2WorldUtil.getRandomAccessibleWorldFromRegion(
                            config.regionPreference().getWorldRegion(),
                            config.avoidEmptyWorlds(),
                            config.avoidOvercrowdedWorlds(),membersOnly);
                        break;
                        
                    case BEST_POPULATION:
                        targetWorld = Rs2WorldUtil.getBestAccessibleWorldForLogin(
                            false,
                            config.regionPreference().getWorldRegion(),
                            config.avoidEmptyWorlds(),
                            config.avoidOvercrowdedWorlds(),                            
                            membersOnly);
                        break;
                        
                    case BEST_PING:
                        targetWorld = Rs2WorldUtil.getBestAccessibleWorldForLogin(
                            true,
                            config.regionPreference().getWorldRegion(),
                            config.avoidEmptyWorlds(),
                            config.avoidOvercrowdedWorlds(),
                            membersOnly
                            );
                        break;
                        
                    case REGIONAL_RANDOM:
                        targetWorld = Rs2WorldUtil.getRandomAccessibleWorldFromRegion(
                            config.regionPreference().getWorldRegion(),
                            config.avoidEmptyWorlds(),
                            config.avoidOvercrowdedWorlds(),
                            membersOnly
                            );
                        break;
                        
                    default:
                        // fallback to legacy behavior                        
                        targetWorld = Login.getRandomWorld(Rs2Player.isMember());                        
                        if(!Rs2WorldUtil.canAccessWorld(targetWorld)) {
                            log.warn("Randomly selected world {} is not accessible, using default world {}", targetWorld, config.world());
                            targetWorld = config.world();
                        }                                                
                        break;
                }
            }
            
            // perform login attempt and track retry state
            retryCount++;
            lastLoginAttemptTime = Instant.now();
            
            if (targetWorld != -1) {
                log.info("Attempting login to selected world: {} (attempt {})", targetWorld, retryCount);
                new Login(targetWorld);
            } else {
                log.info("Using default login (current world or last used) (attempt {})", retryCount);
                new Login();
            }
            
        } catch (Exception ex) {
            log.error("Error during intelligent login", ex);
            retryCount++;
            lastLoginAttemptTime = Instant.now();
        }
    }
    
    /**
     * Transitions to a new login state.
     */
    private void transitionToState(LoginState newState) {
        if (loginState != newState) {
            log.debug("Auto login state transition: {} -> {}", loginState, newState);
            loginState = newState;
        }
    }
    
    /**
     * Resets login state variables.
     */
    private void resetLoginState() {
        retryCount = 0;
        loginWatchdogStartTime = null;
        extendedSleepStartTime = null;
        lastLoginAttemptTime = null;
        lastExtendedSleepLoggedMinute = -1;
    }
    
    @Override
    public void shutdown() {
        log.info("Auto login script shutting down");

        resetLoginState();
        super.shutdown();
        
    }
}
