package de.tum.bgu.msm.health.data;

import de.tum.bgu.msm.data.Mode;

import java.util.HashMap;
import java.util.Map;

// Dose-response functions for health exposures (simple for now but will become more complex)
public class RelativeRisks {

    public static Map<String, Float> calculate(PersonHealth personHealth) {
        Map<String, Float> relativeRisks = new HashMap<>();

        relativeRisks.put("walk", (float) walk(personHealth.getWeeklyMarginalMetHours(Mode.walk)));
        relativeRisks.put("cycle", (float) bike(personHealth.getWeeklyMarginalMetHours(Mode.bicycle)));
        relativeRisks.put("pm2.5", (float) pm25(personHealth.getWeeklyExposureByPollutantNormalised("pm2.5")));
        relativeRisks.put("no2", (float) no2(personHealth.getWeeklyExposureByPollutantNormalised("no2")));

        return relativeRisks;
    }

    // equivalent to 0.9 ^ (mMET / 8.75)
    private static double walk(double mMEThrs) {
        return Math.max(0.7,Math.exp(-0.0120412*mMEThrs));
    }

    // equivalent to 0.9 ^ (mMET / 8.75)
    private static double bike(double mMEThrs) {
        return Math.max(0.55,Math.exp(-0.0120412*mMEThrs));
    }

    // todo: should we cap the air pollution RR at some maximum?
    private static double pm25(double microgramPerM3) {
        return Math.min(2.,Math.exp(0.0076961*microgramPerM3));
    }

    private static double no2(double microgramPerM3) {
        return Math.min(2.,Math.exp(0.0019803*microgramPerM3));
    }
}
