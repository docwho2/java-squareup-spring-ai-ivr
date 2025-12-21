package cloud.cleo.squareup;


import cloud.cleo.squareup.sms.SmsTestSuite;
import cloud.cleo.squareup.voice.VoiceTestSuite;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs all tests under cloud.cleo.squareup.voice
 */
@Suite
@SelectClasses({
        VoiceTestSuite.class,
        SmsTestSuite.class
})
public class FullRegressionSuite {
}