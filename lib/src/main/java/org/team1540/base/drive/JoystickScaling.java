package org.team1540.base.drive;

import org.team1540.base.drive.pipeline.Processor;

@FunctionalInterface
public interface JoystickScaling extends Processor<Double, Double> {

  public double scale(double input);

  public default Double apply(Double input) {
    return scale(input);
  }
}
