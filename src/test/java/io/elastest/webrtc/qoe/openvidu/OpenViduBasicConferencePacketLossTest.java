/*
 * (C) Copyright 2017-2019 ElasTest (http://elastest.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.elastest.webrtc.qoe.openvidu;

import static io.github.bonigarcia.seljup.BrowserType.CHROME;
import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.slf4j.Logger;

import com.spotify.docker.client.exceptions.DockerException;

import io.elastest.webrtc.qoe.ElasTestRemoteControlParent;
import io.github.bonigarcia.seljup.Arguments;
import io.github.bonigarcia.seljup.DockerBrowser;
import io.github.bonigarcia.seljup.SeleniumExtension;

public class OpenViduBasicConferencePacketLossTest
        extends ElasTestRemoteControlParent {

    @RegisterExtension
    static SeleniumExtension seleniumExtension = new SeleniumExtension();

    final Logger log = getLogger(lookup().lookupClass());

    static final String FAKE_FILE_IN_CONTAINER = "--use-file-for-fake-video-capture=/home/selenium/test.y4m";
    static final String SUT_URL = "https://demos.openvidu.io/basic-videoconference/";
    static final int TEST_TIME_SEC = 30;
    static final String PRESENTER_NAME = "presenter";
    static final String VIEWER_NAME = "viewer";
    static final String SESSION_NAME = "qoe-session";
    static final String WEBM_EXT = ".webm";

    WebDriver presenter, viewer;
    String path = "";

    // FIXME: Volume local path requires absolute path (where the file test.y4m
    // is stored in local)
    public OpenViduBasicConferencePacketLossTest(@Arguments({ FAKE_DEVICE,
            FAKE_UI,
            FAKE_FILE_IN_CONTAINER }) @DockerBrowser(type = CHROME, version = "beta", volumes = {
                    "/home/boni/Documents/dev/elastest-webrtc-qoe-meter:/home/selenium" }) WebDriver presenter,
            @Arguments({ FAKE_DEVICE, FAKE_UI }) ChromeDriver viewer) {
        super(SUT_URL, presenter, viewer);
        this.presenter = presenter;
        this.viewer = viewer;
    }

    @BeforeEach
    void setup() throws Exception {
        seleniumExtension.getConfig().setBrowserSessionTimeoutDuration("2m0s");
    }

    private void execCommandInContainer(WebDriver driver, String[] command)
            throws DockerException, InterruptedException {
        Optional<String> containerId = seleniumExtension.getContainerId(driver);
        if (containerId.isPresent()) {
            String container = containerId.get();
            log.debug("Running {} in container {}", Arrays.toString(command),
                    container);

            String result = seleniumExtension.getDockerService()
                    .execCommandInContainer(container, command);
            if (result != null) {
                log.debug("Result: {}", result);
            }
        } else {
            log.warn("Container not present in {}", driver);
        }
    }

    @Test
    void openviduTest() throws Exception {
        // Presenter
        clearAndSendKeysToElementById(presenter, "userName", PRESENTER_NAME);
        clearAndSendKeysToElementById(presenter, "sessionId", SESSION_NAME);
        presenter.findElement(By.name("commit")).click();

        // Viewer
        clearAndSendKeysToElementById(viewer, "userName", VIEWER_NAME);
        clearAndSendKeysToElementById(viewer, "sessionId", SESSION_NAME);
        viewer.findElement(By.name("commit")).click();

        // Start recordings
        startRecording(presenter,
                "session.streamManagers[0].stream.webRtcPeer.pc.getLocalStreams()[0]");
        startRecording(viewer,
                "session.streamManagers[0].stream.webRtcPeer.pc.getRemoteStreams()[0]");

        // Simulate packet loss in viewer container
        String[] tc = { "sudo", "tc", "qdisc", "replace", "dev", "eth0", "root",
                "netem", "loss", "50%" };
        execCommandInContainer(presenter, tc);

        // Wait
        waitSeconds(TEST_TIME_SEC);

        // Clear packet loss
        String[] clear = { "sudo", "tc", "qdisc", "replace", "dev", "eth0",
                "root", "netem", "loss", "0%" };
        execCommandInContainer(presenter, clear);

        // Stop recordings
        stopRecording(presenter);
        stopRecording(viewer);

        File presenterRecording = getRecording(presenter,
                PRESENTER_NAME + WEBM_EXT);
        assertTrue(presenterRecording.exists());

        File viewerRecording = getRecording(viewer, VIEWER_NAME + WEBM_EXT);
        assertTrue(viewerRecording.exists());
    }

}