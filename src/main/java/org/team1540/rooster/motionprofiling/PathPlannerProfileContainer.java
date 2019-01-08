package org.team1540.rooster.motionprofiling;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.jetbrains.annotations.NotNull;
import org.team1540.rooster.motionprofiling.MotionProfile.Point;

/**
 * A {@link ProfileContainer} for loading paths generated by
 * <a href="https://github.com/mjansen4857/PathPlanner">PathPlanner</a>. The profiles should be in
 * the format "p,v,a,h".
 */
public class PathPlannerProfileContainer extends ProfileContainer {


  private final double dt;

  /**
   * Creates a new {@code ProfileContainer} that searches the provided directory using a left suffix
   * of "{@code _left.csv}" and a right suffix of "{@code _right.csv}"
   *
   * This constructor also searches the provided directory for profiles and loads all the profiles
   * into RAM. For this reason, initialization may take some time (especially for large amounts of
   * profiles).
   *
   * @param dt The delta-time of the profile, in seconds.
   * @param profileDirectory The directory containing the profiles. See the {@linkplain
   * ProfileContainer class documentation} for a description of the folder structure.
   * @throws RuntimeException If an I/O error occurs during profile loading.
   */
  public PathPlannerProfileContainer(double dt, @NotNull File profileDirectory) {
    super(profileDirectory);
    this.dt = dt;
  }

  /**
   * Creates a new {@code ProfileContainer}. This constructor also searches the provided directory
   * for profiles and loads all the profiles into RAM. For this reason, initialization may take some
   * time (especially for large amounts of profiles).
   *
   * @param dt The delta-time of the profile, in seconds.
   * @param profileDirectory The directory containing the profiles. See the {@linkplain
   * ProfileContainer class documentation} for a description of the folder structure.
   * @param leftSuffix The suffix to use to identify left-side profile files.
   * @param rightSuffix The suffix to use to identify right-side profile files.
   * @throws RuntimeException If an I/O error occurs during profile loading.
   */
  public PathPlannerProfileContainer(double dt, @NotNull File profileDirectory,
      @NotNull String leftSuffix, @NotNull String rightSuffix) {
    super(profileDirectory, leftSuffix, rightSuffix);
    this.dt = dt;
  }

  @NotNull
  @Override
  protected MotionProfile readProfile(@NotNull File file) throws IOException {
    return new MotionProfile(Files.readAllLines(file.toPath()).stream().map(s -> {
      String[] arr = s.split(",");
      double pos = Double.valueOf(arr[0]);
      double vel = Double.valueOf(arr[1]);
      double acc = Double.valueOf(arr[2]);
      double hdg = Double.valueOf(arr[3]);

      return new Point(dt, 0, 0, pos, vel, acc, 0, hdg);
    }).toArray(Point[]::new));
  }
}
