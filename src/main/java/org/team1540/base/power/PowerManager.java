package org.team1540.base.power;

import edu.wpi.first.wpilibj.PowerDistributionPanel;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.Timer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/*
A word on language: Management is if this is running, scaling is if the power is actually being set to be something different.
 */

// Reminder that everything will need to be thread safe
@SuppressWarnings("unused")
public class PowerManager extends Thread {

  // Singleton
  private static PowerManager theManager = new PowerManager();

  static {
    theManager.start();
  }

  private int updateDelay = 5;

  private double currentSpikePeak = 50;
  private double currentSpikeLength = 2.0;
  private double currentTarget = 40;
  private double currentMargin = 5;
  private final Timer currentTimer = new Timer();
  private final Timer voltageTimer = new Timer();
  /**
   * Default to be a little higher than brownouts.
   */
  private double voltageDipLow = 7.2;
  private double voltageMargin = 0.5;
  private double voltageDipLength = 0;

  private boolean running = true;

  // Store the currently running PowerManageables
  // For the love of everything, so there are no race conditions, do not access this except though synchronized blocks
  private final Set<PowerManageable> powerManaged = Collections.synchronizedSet(new HashSet<>());
  private final Object powerLock = new Object();
  // Because we gotta grab the power info off of it
  private final PowerDistributionPanel pdp = new PowerDistributionPanel();

  private PowerManager() {}

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
        // ***EWWWW*** bad coding
        if (!(checkPowerStatus(currentIsSpiking(), currentTimeHasPassed(), currentTimer) &&
            checkPowerStatus(voltageIsDipping(), voltageTimeHasPassed(), voltageTimer))) {
          stopScaling();
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
   * Checks and runs power scaling based on parameters.
   *
   * @param shouldRun Boolean indicating if power scaling should be tested
   * @param shouldScale Boolean indicating if the timer has exceeded the running time.
   * @param timer The timer object clocking the time.
   * @return shouldRun
   */
  private boolean checkPowerStatus(boolean shouldRun, boolean shouldScale, Timer timer) {
    if (shouldRun) {
      if (timer.get() <= 0) {
        // Calling the timer when it's already started seems to reset it.
        timer.start();
      }
      if (shouldScale) {
        scalePower();
      }
    } else {
      timer.stop();
      timer.reset();
    }
    return shouldRun;
  }

  /**
   * Separate method to block PowerManageable registration/unregistration while actually scaling the
   * power.
   */
  private void scalePower() {
    synchronized (powerLock) {

      Map<PowerManageable, Double> powerManageableCurrents = new LinkedHashMap<>();

      // Find out what the highest priority is
      double highestPriority = Collections.max(powerManaged).getPriority();

      // For each PowerManageable, pass the priority into an arbitrary function, multiply that value by the
      // actual current draw, and store it in a map along with a running tally of the total
      double totalScaledCurrent = 0;
      for (PowerManageable currentManageable : powerManaged) {
        double scaledCurrent =
            scaleExponential(highestPriority, currentManageable.getPriority()) * currentManageable
                .getCurrent();
        powerManageableCurrents.put(currentManageable, scaledCurrent);
        totalScaledCurrent += scaledCurrent;
      }

      // Find a factor such that the new total equals the currentTarget
      double factor = currentTarget / totalScaledCurrent;

      // Multiply that factor by the ratio between the new power and the actual power and pass that
      // back to the PowerManageable
      for (PowerManageable currentManageable : powerManaged) {
        currentManageable.limitPower(powerManageableCurrents.get(currentManageable) * factor);
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
   * Determines if the current is currently spiking. If power limiting is not engaged,
   * returns pdp.getTotalCurrent() &gt; currentSpikePeak.
   * If power limiting is engaged, returns pdp.getTotalCurrent() &gt; currentTarget - currentMargin.
   *
   * @return Boolean representing if the current is spiking.
   */
  public boolean currentIsSpiking() {
    if (!currentTimeHasPassed()) {
      return pdp.getTotalCurrent() > currentSpikePeak;
    } else {
      return pdp.getTotalCurrent() > currentTarget - currentMargin;
    }
  }

  /**
   * Determines if the voltage is currently spiking. If power limiting is not engaged,
   * returns RobotController.getBatteryVoltage() &lt; voltageDipLow || RobotController.isBrownedOut();
   * If power limiting is engaged, returns pdp.getVoltage() &lt; voltageDipLow + voltageMargin ||
   * RobotController.isBrownedOut();.
   *
   * @return Boolean representing if the voltage is spiking.
   */
  public boolean voltageIsDipping() {
    if (!voltageTimeHasPassed()) {
      return RobotController.getBatteryVoltage() < voltageDipLow || RobotController.isBrownedOut();
    } else {
      return RobotController.getBatteryVoltage() < voltageDipLow + voltageMargin || RobotController
          .isBrownedOut();
    }
  }

  private boolean currentTimeHasPassed() {
    return (currentTimer.get() > currentSpikeLength);
  }

  private boolean voltageTimeHasPassed() {
    return (voltageTimer.get() > voltageDipLength);
  }

  /**
   * Determine if power limiting has kicked in.
   *
   * @return True if power limiting has kicked in, false otherwise
   */
  public boolean isLimiting() {
    return (currentTimeHasPassed() && currentIsSpiking()) || (voltageTimeHasPassed()
        && voltageIsDipping());
  }

  /**
   * Run an arbitrary function to scale the priority of a given {@link PowerManageable}. <p> Currently uses
   * inverse natural exponential For those who like LaTeX, here's the function, where h is the
   * highest priority and x is the priority \frac{h}{e^{\left(h-x\right)}}
   *
   * @param highestPriority The priority of the highest priority {@link PowerManageable} currently running.
   * @param priority The priority of this {@link PowerManageable}.
   *
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
   *
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

  public double getCurrentSpikePeak() {
    return currentSpikePeak;
  }

  /**
   * Sets the required current value for the robot to be considered spiking. Defaults to 50A.
   *
   * @param currentSpikePeak The minimum spike value, in amps.
   */
  public void setCurrentSpikePeak(double currentSpikePeak) {
    this.currentSpikePeak = currentSpikePeak;
  }

  public double getCurrentSpikeLength() {
    return currentSpikeLength;
  }

  /**
   * Sets how long the current must spike for before doing anything. Defaults to 2 seconds.
   *
   * @param currentSpikeLength The minimum actionable spike length, in seconds.
   */
  public void setCurrentSpikeLength(double currentSpikeLength) {
    this.currentSpikeLength = currentSpikeLength;
  }

  public double getCurrentTarget() {
    return currentTarget;
  }

  /**
   * Sets the currentTarget value we want when starting to power-manage. Defaults to 40A.
   *
   * @param currentTarget The currentTarget value, in amps.
   */
  public void setCurrentTarget(double currentTarget) {
    this.currentTarget = currentTarget;
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
   * Gets the currentMargin below which, if power limiting has engaged, power management will remain
   * engaged. Defaults to 5A.
   *
   * @return currentMargin in amps.
   */
  public double getCurrentMargin() {
    return currentMargin;
  }

  /**
   * Set the currentMargin within which, if power limiting has engaged, power management will remain
   * engaged. Defaults to 5A.
   *
   * @param currentMargin currentMargin in amps.
   */
  public void setCurrentMargin(double currentMargin) {
    this.currentMargin = currentMargin;
  }

  /**
   * Gets the highest current time on any of the internal timers representing time from the most
   * recent spike or dip.
   *
   * @return Double representing time.
   */
  public double getPowerTime() {
    return currentTimer.get() > voltageTimer.get() ? currentTimer.get() : voltageTimer.get();
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
   * Gets the voltageMargin within which, if power limiting has engaged, power management will remain
   * engaged. Defaults to 0.5V.
   *
   * @return voltageMargin in volts.
   */
  public double getVoltageMargin() {
    return voltageMargin;
  }

  /**
   * Sets the voltageMargin within which, if power limiting has engaged, power management will remain
   * engaged. Defaults to 0.5V.
   *
   * @param voltageMargin in volts.
   */
  public void setVoltageMargin(double voltageMargin) {
    this.voltageMargin = voltageMargin;
  }
}
