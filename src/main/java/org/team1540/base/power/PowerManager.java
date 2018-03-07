package org.team1540.base.power;

import edu.wpi.first.wpilibj.PowerDistributionPanel;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.Sendable;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.smartdashboard.SendableBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/*
A word on language: Management is if this is running, scaling is if the power is actually being set
to be something different.
 */

// Reminder that everything will need to be thread safe
@SuppressWarnings("unused")
public class PowerManager extends Thread implements Sendable {

  private static String name = "PowerManager";

  // Singleton
  private static PowerManager theManager = new PowerManager();

  static {
    theManager.start();
  }

  private int updateDelay = 5;

  /**
   * Default to be a little higher than brownouts.
   */
  private double voltageDipLow = 7.5;
  private double voltageMargin = 0.5;
  private double voltageDipLength = 0.25;

  private double voltageTarget = 8.0;

  private final Timer voltageTimer = new Timer();
  private boolean running = true;

  // Store the currently running PowerManageables
  // For the love of everything, so there are no race conditions, do not access this except though
  // synchronized blocks
  private final Set<PowerManageable> powerManaged = Collections.synchronizedSet(new HashSet<>());
  private final Object powerLock = new Object();
  // Because we gotta grab the power info off of it
  private final PowerDistributionPanel pdp = new PowerDistributionPanel();

  private PowerManager() {
  }

  /**
   * Gets the PowerManager.
   *
   * @return The singleton PowerManager instance.
   */
  public static PowerManager getInstance() {
    return theManager;
  }

  @Override
  public void run() {
    while (true) {
      // No whiles in here as that'd stop the last block from executing
      if (running) {
        if (isVoltageDipping()) {
          if (voltageTimer.get() <= 0) {
            // Calling the timer when it's already started seems to reset it.
            voltageTimer.start();
          }
        } else {
          voltageTimer.stop();
          voltageTimer.reset();
          stopScaling();
        }

        if (hasTimePassedVoltage()) {
          scalePower();
        }
      }

      try {
        sleep(updateDelay);
      } catch (InterruptedException e) {
        // end the thread
        return;
      }
    }
  }

  /**
   * Separate method to block PowerManageable registration/unregistration while actually scaling the
   * power.
   */
  private void scalePower() {
    synchronized (powerLock) {

      Map<PowerManageable, Double> manageablePowers = new LinkedHashMap<>();
      Map<PowerManageable, Double> manageablePowersScaled = new LinkedHashMap<>();

      // Find out what the highest priority is
      double highestPriority = Collections.max(powerManaged).getPriority();

      // For each PowerManageable, pass the priority into an arbitrary function, multiply that value
      // by the actual power consumption, and store it in a map along with a running tally of the
      // total
      // Also store the real power draw
      double totalScaledPower = 0;
      for (PowerManageable thisManageable : powerManaged) {
        double powerConsumption = thisManageable.getPowerConsumption();
        double powerConsumptionScaled = scaleExponential(highestPriority,
            thisManageable.getPriority()) * powerConsumption;
        manageablePowers.put(thisManageable, powerConsumption);
        manageablePowersScaled.put(thisManageable, powerConsumptionScaled);
        totalScaledPower += powerConsumptionScaled;
      }

      // Find a factor such that the totalScaledPower * that factor = the target
      double factor = voltageTarget * pdp.getTotalCurrent() / totalScaledPower;

      // Multiply each scaled power by the factor, which gets us our real target power. Then, divide
      // that by the original power draw to get the percent output we want.
      for (PowerManageable currentManageable : powerManaged) {
        currentManageable
            .setPercentOutputLimit(manageablePowersScaled.get(currentManageable) * factor /
                manageablePowers.get(currentManageable));
      }
    }
  }

  /**
   * Separate method to block PowerManageable registration/deregistration while stopping scaling.
   */
  private void stopScaling() {
    synchronized (powerLock) {
      for (PowerManageable currentManageable : powerManaged) {
        currentManageable.stopLimitingPower();
      }
    }
  }

  /**
   * Determines if the voltage is currently dipping. If power limiting is not engaged,
   * returns RobotController.getBatteryVoltage() &lt; voltageDipLow || RobotController.
   * isBrownedOut();
   * If power limiting is engaged, returns pdp.getVoltage() &lt; voltageDipLow + voltageMargin ||
   * RobotController.isBrownedOut();.
   *
   * @return Boolean representing if the voltage is dipping.
   */
  public boolean isVoltageDipping() {
    if (!hasTimePassedVoltage()) {
      return RobotController.getBatteryVoltage() < voltageDipLow || RobotController.isBrownedOut();
    } else {
      return RobotController.getBatteryVoltage() < voltageDipLow + voltageMargin || RobotController
          .isBrownedOut();
    }
  }

  private boolean hasTimePassedVoltage() {
    return (voltageTimer.get() > voltageDipLength);
  }

  /**
   * Determine if power limiting has kicked in.
   *
   * @return True if power limiting has kicked in, false otherwise
   */
  public boolean isLimiting() {
    return hasTimePassedVoltage() && isVoltageDipping();
  }

  /**
   * Run an arbitrary function to scale the priority of a given {@link PowerManageable}. <p> Currently uses
   * inverse natural exponential For those who like LaTeX, here's the function, where h is the
   * highest priority and x is the priority \frac{h}{e^{\left(h-x\right)}}
   *
   * @param highestPriority The priority of the highest priority {@link PowerManageable} currently running.
   * @param priority The priority of this {@link PowerManageable}.
   * @return The scale factor for this {@link PowerManageable}.
   */
  private double scaleExponential(double highestPriority, double priority) {
    return highestPriority / (Math.exp(highestPriority - priority));
  }

  /**
   * Registers the {@link PowerManageable} as being used. Blocks power scaling.
   *
   * @param toRegister The {@link PowerManageable} to register.
   * @return true if the PowerManager did not already contain the specified element
   */
  public boolean registerPowerManageable(PowerManageable toRegister) {
    synchronized (powerLock) {
      return powerManaged.add(toRegister);
    }
  }

  /**
   * Registers a group of {@link PowerManageable}s. Calls registerPowerManager().
   *
   * @param toRegister The {@link PowerManageable}s to register.
   * @return A map of PowerManageables with the key true if the PowerManager did not already contain
   * the specified element
   */
  public Map<PowerManageable, Boolean> registerPowerManageables(
      PowerManageable... toRegister) {
    HashMap<PowerManageable, Boolean> success = new HashMap<>();
    for (PowerManageable register : toRegister) {
      success.put(register, registerPowerManageable(register));
    }
    return success;
  }

  /**
   * Unregisters the {@link PowerManageable} as being used. Blocks power scaling.
   *
   * @param toUnregister The {@link PowerManageable} to unregister.
   * @return true if the PowerManager contained the specified element
   */
  public boolean unregisterPowerManageable(PowerManageable toUnregister) {
    synchronized (powerLock) {
      return powerManaged.remove(toUnregister);
    }
  }

  /**
   * Unregisters a group of {@link PowerManageable}s. Calls unregisterPowerManager().
   *
   * @param toUnregister The {@link PowerManageable}s to unregister.
   * @return A map of PowerManageables with the key true if the PowerManager contained the specified
   * element
   */
  public Map<PowerManageable, Boolean> unregisterPowerManageables(
      PowerManageable... toUnregister) {
    HashMap<PowerManageable, Boolean> success = new HashMap<>();
    for (PowerManageable unregister : toUnregister) {
      success.put(unregister, unregisterPowerManageable(unregister));
    }
    return success;
  }

  public int getUpdateDelay() {
    return updateDelay;
  }

  /**
   * Sets the time between power management cycles. Defaults to 5ms.
   *
   * @param updateDelay The time between power management cycles, in milliseconds.
   */
  public void setUpdateDelay(int updateDelay) {
    this.updateDelay = updateDelay;
  }

  public boolean isRunning() {
    return running;
  }

  /**
   * Sets the state of the power manager. Set to {@code true} to enable power management, set to
   * {@code false} to disable management.
   *
   * @param running The state of the power manager.
   */
  public void setRunning(boolean running) {
    this.running = running;
  }

  /**
   * Gets the highest current time on any of the internal timers representing time from the most
   * recent spike or dip.
   *
   * @return Double representing time.
   */
  public double getPowerTime() {
    return voltageTimer.get();
  }

  /**
   * Gets the required voltage value for the robot to be considered dipping. Defaults to 7.2V.
   *
   * @return voltageDipLow The minimum dip value, in volts.
   */
  public double getVoltageDipLow() {
    return voltageDipLow;
  }

  /**
   * Sets the required voltage value for the robot to be considered dipping. Defaults to 7.2V.
   *
   * @param voltageDipLow The minimum dip value, in volts.
   */
  public void setVoltageDipLow(double voltageDipLow) {
    this.voltageDipLow = voltageDipLow;
  }

  /**
   * Gets how long the voltage must dip for before doing anything. Defaults to 0 seconds.
   *
   * @return voltageDipLength The minimum actionable spike length, in seconds.
   */
  public double getVoltageDipLength() {
    return voltageDipLength;
  }

  /**
   * Sets how long the voltage must dip for before doing anything. Defaults to 0 seconds.
   *
   * @param voltageDipLength The minimum actionable spike length, in seconds.
   */
  public void setVoltageDipLength(double voltageDipLength) {
    this.voltageDipLength = voltageDipLength;
  }

  /**
   * Gets the voltageMargin within which, if power limiting has engaged, power management will
   * remain engaged. Defaults to 0.5V.
   *
   * @return voltageMargin in volts.
   */
  public double getVoltageMargin() {
    return voltageMargin;
  }

  /**
   * Sets the voltageMargin within which, if power limiting has engaged, power management will
   * remain engaged. Defaults to 0.5V.
   *
   * @param voltageMargin in volts.
   */
  public void setVoltageMargin(double voltageMargin) {
    this.voltageMargin = voltageMargin;
  }

  public double getVoltageTarget() {
    return voltageTarget;
  }

  public void setVoltageTarget(double voltageTarget) {
    this.voltageTarget = voltageTarget;
  }

  @Override
  public String getSubsystem() {
    return name;
  }

  @Override
  public void setSubsystem(String subsystem) {
    name = subsystem;
  }

  @Override
  public void initSendable(SendableBuilder builder) {
    builder.setSmartDashboardType("PowerManager");
    builder.addBooleanProperty("isVoltageDipping", this::isVoltageDipping, null);
    builder.addBooleanProperty("isLimiting", this::isLimiting, null);
    builder.addDoubleProperty("powerTime", this::getPowerTime, null);
    builder.addBooleanProperty("running", this::isRunning, this::setRunning);
    builder.addDoubleProperty("updateDelay", this::getUpdateDelay,
        value -> setUpdateDelay(Math.toIntExact(Math.round(value))));
    builder.addDoubleProperty("voltageDipLow", this::getVoltageDipLow, this::setVoltageDipLow);
    builder.addDoubleProperty("voltageMargin", this::getVoltageMargin, this::setVoltageMargin);
    builder.addDoubleProperty("voltageDipLength", this::getVoltageDipLength,
        this::setVoltageDipLength);
    builder.addDoubleProperty("voltageTarget", this::getVoltageTarget, this::setVoltageTarget);
    // Maybe add all the registered PowerManageables later?
  }
}