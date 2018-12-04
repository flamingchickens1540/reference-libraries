package org.team1540.rooster.motionprofiling;

import edu.wpi.first.wpilibj.DriverStation;
import jaci.pathfinder.Pathfinder;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Class to preload motion profiles from a specified folder on disk. The preloaded motion profiles
 * are stored in RAM so as to be quickly accessible.
 *
 * <p>The folder provided should contain profile CSV files (formatted so as to be read by {@link
 * Pathfinder#readFromCSV(File)}) where each profile named {@code name} is stored in two files,
 * {@code name}_left.csv for the left-side profile and {@code name}_right.csv for the right-side
 * profile. An example folder structure would be as follows (where the profiles/ directory is
 * provided to the constructor):
 *
 * <pre>
 * profiles/
 *   foo_left.csv
 *   foo_right.csv
 *   bar_left.csv
 *   bar_right.csv
 * </pre>
 *
 * This would cause the {@code ProfileContainer} to load two profiles named {@code foo} and {@code
 * bar}.
 *
 * <p>This class is immutable after instantiation.
 */
public class ProfileContainer {

  @NotNull
  private Map<String, DriveProfile> profiles;

  /**
   * Creates a new {@code ProfileContainer}. This constructor also searches the provided directory
   * for profiles and loads all the profiles into RAM. For this reason, initialization may take some
   * time (especially for large amounts of profiles).
   *
   * @param profileDirectory The directory containing the profiles. See the {@linkplain
   * ProfileContainer class documentation} for a description of the folder structure.
   * @throws RuntimeException If an I/O error occurs during profile loading.
   */
  public ProfileContainer(@NotNull File profileDirectory) {
    if (!profileDirectory.isDirectory()) {
      throw new IllegalArgumentException("Not a directory");
    }

    File[] lFiles = profileDirectory.listFiles((file) -> file.getName().endsWith("_left.csv"));
    File[] rFiles = profileDirectory.listFiles((file) -> file.getName().endsWith("_right.csv"));

    if (lFiles == null || rFiles == null) {
      // according to listFiles() docs, it will only return null if the file isn't a directory
      // (which we've already checked) or if an IO error occurs. Thus, if lFiles or rFiles is
      // null we know an IO error happened. Not throwing an IOException because checked exceptions
      // are bad, in constructors even more so.
      throw new RuntimeException("IO error occurred while reading files");
    }

    Set<String> profileNames = new HashSet<>();

    for (File f : lFiles) {
      profileNames.add(f.getName().substring(0, f.getName().length() - "_left.csv".length()));
    }

    for (File f : rFiles) {
      profileNames.add(f.getName().substring(0, f.getName().length() - "_right.csv".length()));
    }

    // initialize the map once we know the number of profiles so it doesn't expand.
    // Why? p e r f o r m a n c e

    profiles = new HashMap<>(profileNames.size());

    for (String name : profileNames) {
      System.out.println("Loading profile " + name);
      File leftFile = Arrays.stream(lFiles)
          .filter(file -> file.getName().equals(name + "_left.csv"))
          .findFirst()
          .orElseGet(() -> {
            DriverStation
                .reportWarning("Left-side file for profile " + name + " does not exist", false);
            return null;
          });
      File rightFile = Arrays.stream(rFiles)
          .filter(file -> file.getName().equals(name + "_right.csv"))
          .findFirst()
          .orElseGet(() -> {
            DriverStation
                .reportWarning("Right-side file for profile " + name + " does not exist", false);
            return null;
          });

      if (leftFile != null && rightFile != null) {
        MotionProfile left = MotionProfileUtils.createProfile(Pathfinder.readFromCSV(leftFile));
        MotionProfile right = MotionProfileUtils.createProfile(Pathfinder.readFromCSV(rightFile));

        profiles.put(name, new DriveProfile(left, right));
      }
    }
  }

  /**
   * Returns the motion profile set with the specified name, or {@code null} if the profile does not
   * exist.
   *
   * @param name The name of the profile.
   * @return A {@link DriveProfile} containing the left and right profiles, or {@code null} if the
   * profile does not exist.
   */
  @Nullable
  public DriveProfile get(String name) {
    return profiles.get(name);
  }

  /**
   * Get the {@link Set} of profile names in the {@code ProfileContainer}.
   *
   * @return A {@link Set} containing the names of each profile.
   */
  @NotNull
  public Set<String> getProfileNames() {
    return profiles.keySet();
  }

  /**
   * Returns whether or not the {@code ProfileContainer} contains a specific profile.
   *
   * @param name The name of the profile to check.
   * @return {@code true} if the profile exists (i.e. if {@link #get(String) get(name)} would not
   * return {@code null}), {@code false} otherwise.
   */
  public boolean hasProfile(String name) {
    return profiles.containsKey(name);
  }

  /**
   * Data class for holding left and right trajectories in one object.
   */
  public static class DriveProfile {

    @NotNull
    private final MotionProfile left;
    @NotNull
    private final MotionProfile right;

    private DriveProfile(@NotNull MotionProfile left, @NotNull MotionProfile right) {
      this.left = left;
      this.right = right;
    }

    @NotNull
    public MotionProfile getLeft() {
      return left;
    }

    @NotNull
    public MotionProfile getRight() {
      return right;
    }
  }
}
