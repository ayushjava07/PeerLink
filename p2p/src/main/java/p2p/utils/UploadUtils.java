package p2p.utils;

import java.util.Random;

public class UploadUtils {
    public static int getRandomPort() {
    int DYNAMIC_STARTING_PORT = 49522;
    int DYNAMIC_ENDING_PORT = 95535;

    Random random = new Random();
    return random.nextInt((DYNAMIC_ENDING_PORT - DYNAMIC_STARTING_PORT) + DYNAMIC_STARTING_PORT);
  }
}