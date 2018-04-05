package org.team1540.base;

import com.ctre.phoenix.motorcontrol.ControlMode;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SendableBuilder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.team1540.base.power.PowerManageable;
import org.team1540.base.power.PowerManager;
import org.team1540.base.power.PowerTelemetry;
import org.team1540.base.wrappers.ChickenController;
import org.team1540.base.wrappers.ChickenTalon;
import org.team1540.base.wrappers.ChickenVictor;


/**
 * Simple implementation of core {@link Subsystem} related interfaces. Makes it quick and easy to
 * build a basic robot.
 */
@SuppressWarnings("unused")
public class ChickenSubsystem extends Subsystem implements PowerManageable {

  /**
   * Map of motors in this subsystem to be power managed, with the key being the motor and the value
   * being the current percentOutput the motor is at.
   */
  private final Map<ChickenController, MotorProperties> motors = new ConcurrentHashMap<>();
  private final PowerTelemetry allMotorTelemetry = new PowerTelemetry() {
    @Override
    public double getCurrent() {
      double sum = 0;
      for (ChickenController motor : motors.keySet()) {
        if (motor instanceof ChickenTalon) {
          sum += motor.getOutputCurrent();
        }
      }
      return sum;
    }

    @Override
    public double getVoltage() {
      double sum = 0;
      for (ChickenController motor : motors.keySet()) {
        sum += motor.getMotorOutputVoltage();
      }
      return sum / motors.size();
    }
  };
  private double noiseThreshold = 0.25;
  private double priority = 1.0;
  private boolean telemetryCacheValid = false;
  private PowerTelemetry telemetry = allMotorTelemetry;

  public ChickenSubsystem(String name) {
    super(name);
    realConstructor();
  }

  public ChickenSubsystem() {
    super();
    realConstructor();
  }

  public int size() {
    return motors.size();
  }

  public boolean isEmpty() {
    return motors.isEmpty();
  }

  public boolean contains(@NotNull ChickenController o) {
    return motors.containsKey(o);
  }

  public void add(@NotNull ChickenController o) {
    invalidateTelemetryCache();
    motors.put(o, new MotorProperties());
  }

  public void add(@NotNull ChickenController... os) {
    addAll(Arrays.asList(os));
  }

  public void remove(@NotNull ChickenController o) {
    invalidateTelemetryCache();
    motors.remove(o);
  }

  public void remove(@NotNull ChickenController... os) {
    removeAll(Arrays.asList(os));
  }

  public boolean containsAll(@NotNull Collection<ChickenController> controllers) {
    for (ChickenController c : controllers) {
      if (!contains(c)) {
        return false;
      }
    }
    return true;
  }

  public void addAll(@NotNull Collection<? extends ChickenController> controllers) {
    for (ChickenController c : controllers) {
      add(c);
    }
  }

  public void removeAll(@NotNull Collection<ChickenController> controllers) {
    for (ChickenController c : controllers) {
      remove(c);
    }
  }

  public void clear() {
    invalidateTelemetryCache();
    motors.clear();
  }

  private void realConstructor() {
    PowerManager.getInstance().registerPowerManageable(this);
  }

  @Override
  protected void initDefaultCommand() {

  }

  @Override
  public double getPriority() {
    return priority;
  }

  @Override
  public void setPriority(double priority) {
    this.priority = priority;
  }

  @Override
  public double getPercentOutputLimit() {
    double sum = 0;
    for (ChickenController currentMotor : motors.keySet()) {
      sum += Math.abs(currentMotor.getMotorOutputPercent());
    }
    return sum / motors.size();
  }

  @Override
  public double setPercentOutputLimit(double limit) {
    double overflow = 0;
    if (Double.isNaN(limit)) {
      DriverStation.reportError(this.getName() + ": Cannot set percentOutputLimit to NaN, "
          + "passing", false);
      return 0;
    }
    for (ChickenController currentMotor : motors.keySet()) {
      final double newLimit = Math.abs(currentMotor.getMotorOutputPercent()) * limit;
      final double ceiling = motors.get(currentMotor).getAbsolutePeakOutputCeilingForward();

      double forwardNewLimit = newLimit;
      double reverseNewLimit = newLimit;
      double forwardCeiling = motors.get(currentMotor).getAbsolutePeakOutputCeilingForward();
      double reverseCeiling = motors.get(currentMotor).getAbsolutePeakOutputCeilingReverse();

      if (forwardNewLimit > forwardCeiling) {
        forwardNewLimit = ceiling;
        overflow += forwardNewLimit - forwardCeiling;
      }
      if (reverseNewLimit > reverseCeiling) {
        reverseNewLimit = ceiling;
        overflow += reverseNewLimit - reverseCeiling;
      }

      // If the new limit is below the threshold, introduce some noise to keep it from being
      // stuck at 0
      if (forwardNewLimit < noiseThreshold) {
        forwardNewLimit = Math.random() * noiseThreshold;
      }
      if (reverseNewLimit < noiseThreshold) {
        reverseNewLimit = Math.random() * noiseThreshold;
      }

      currentMotor.configPeakOutputForward(forwardNewLimit);
      currentMotor.configPeakOutputReverse(-reverseNewLimit);
    }
    // Average overflow (note the divide by two because of the forward and backwards)
    // It's not perfect, but it should be good enough
    return overflow / (motors.size() * 2);
  }

  @Override
  public void stopLimitingPower() {
    for (ChickenController currentMotor : motors.keySet()) {
      final double ceiling = motors.get(currentMotor).getAbsolutePeakOutputCeilingForward();
      currentMotor.configPeakOutputForward(ceiling);
      currentMotor.configPeakOutputReverse(-ceiling);
    }
  }

  public boolean isTelemetryCacheValid() {
    return telemetryCacheValid;
  }

  public void invalidateTelemetryCache() {
    telemetryCacheValid = false;
  }

  private void setTelemetry(PowerTelemetry t) {
    telemetry = t;
    telemetryCacheValid = true;
  }

  /**
   * Gets the threshold below which the output will be randomized to prevent the output from
   * being stuck at 0.
   *
   * @return A float between 0 and 1 inclusive.
   */
  public double getNoiseThreshold() {
    return noiseThreshold;
  }

  /**
   * Gets the threshold below which the output will be randomized to prevent the output from
   * being stuck at 0.
   *
   * @param noiseThreshold A float between 0 and 1 inclusive.
   */
  public void setNoiseThreshold(double noiseThreshold) {
    if (noiseThreshold < 0 || noiseThreshold > 1) {
      throw new IllegalArgumentException("noiseThreshold must be between 0 and 1 inclusive, got "
          + noiseThreshold);
    }
    this.noiseThreshold = noiseThreshold;
  }


  /**
   * Returns an object that gives the aggregate data from {@link ChickenTalon}s if all motors are
   * either {@link ChickenTalon}s or slaved {@link ChickenVictor}s.
   * Else, returns null.
   * Basically, either the entire subsystem has telemetry or none of it does.
   * Note that to improve performance, this status is cached and only updated when either a motor
   * is added, removed, or the cache is externally invalidaded using
   * {@link #invalidateTelemetryCache()}.
   *
   * Override me as necessary (e.g. for all-Victor subsystems where you'd be getting the
   * telemetry from the PDP.)
   *
   * @return The according {@link PowerTelemetry} object.
   */
  @Override
  public Optional<PowerTelemetry> getPowerTelemetry() {
    if (telemetryCacheValid) {
      return Optional.ofNullable(telemetry);
    } else {
      // This unforunately needs to be checked at runtime, as if a Victor is slaved can change
      // really at any time
      for (ChickenController currentMotor : motors.keySet()) {
        if (currentMotor instanceof ChickenVictor && !currentMotor.getControlMode().equals
            (ControlMode.Follower)) {
          setTelemetry(null);
          return Optional.empty();
        }
      }
      setTelemetry(allMotorTelemetry);
      return Optional.of(allMotorTelemetry);
    }
  }

  @Override
  public void initSendable(SendableBuilder builder) {
    super.initSendable(builder);
    sendablePowerInfo(builder);
    builder.addBooleanProperty("telemetryCacheValid", this::isTelemetryCacheValid, null);
    builder.addDoubleProperty("noiseThreshold", this::getNoiseThreshold, this::setNoiseThreshold);
  }

  public double getAbsolutePeakOutputCeilingForward(ChickenController m) {
    return motors.get(m).getAbsolutePeakOutputCeilingForward();
  }

  public void setAbsolutePeakOutputCeilingForward(ChickenController m,
      double absolutePeakOutputCeiling) {
    motors.get(m).setAbsolutePeakOutputCeilingForward(absolutePeakOutputCeiling);
  }

  public double getAbsolutePeakOutputCeilingReverse(ChickenController m) {
    return motors.get(m).getAbsolutePeakOutputCeilingReverse();
  }

  public void setAbsolutePeakOutputCeilingReverse(ChickenController m,
      double absolutePeakOutputCeiling) {
    motors.get(m).setAbsolutePeakOutputCeilingReverse(absolutePeakOutputCeiling);
  }

  public class MotorProperties {

    @NotNull
    private final AtomicLong absolutePeakOutputCeilingForward = new AtomicLong(Double
        .doubleToLongBits(1.0));
    private final AtomicLong absolutePeakOutputCeilingReverse = new AtomicLong(Double
        .doubleToLongBits(1.0));

    public double getAbsolutePeakOutputCeilingForward() {
      return absolutePeakOutputCeilingForward.doubleValue();
    }

    public void setAbsolutePeakOutputCeilingForward(double absolutePeakOutputCeilingForward) {
      this.absolutePeakOutputCeilingForward
          .set(Double.doubleToLongBits(absolutePeakOutputCeilingForward));
    }

    public double getAbsolutePeakOutputCeilingReverse() {
      return absolutePeakOutputCeilingReverse.doubleValue();
    }

    public void setAbsolutePeakOutputCeilingReverse(double absolutePeakOutputCeilingReverse) {
      this.absolutePeakOutputCeilingReverse
          .set(Double.doubleToLongBits(absolutePeakOutputCeilingReverse));
    }

  }

}
