import gearth.Main;
import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionFormLauncher;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.parsers.HAction;
import gearth.extensions.parsers.HEntityUpdate;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.ui.GEarthController;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import misc.Functions;

import java.util.Arrays;

@ExtensionInfo(
        Title =  "G-Click",
        Description =  "Advanced clicking tools",
        Version =  "0.2",
        Author =  "sirjonasxx"
)
public class GClick extends ExtensionForm {

    public CheckBox chkClickthrough;
    public Button enableBtn;
    public Label stateLbl;
    public CheckBox chkAlwaysOnTop;
    public AnchorPane statePane;

    private volatile long latestPingTimestamp = -1;

    private volatile int ping = 45;
    private volatile double pingVariation = 10;
    private volatile long latestRoomTick = -1;

    private volatile long latestWalkTimestamp = -1;


    private volatile boolean rechargerEnabled = false;
    private volatile boolean awaitFridge = false;
    private volatile int fridge = -1;

    private volatile boolean clickthroughEnabled = false;

    @Override
    protected void initExtension() {
        intercept(HMessage.Direction.TOSERVER, "LatencyPingRequest", hMessage -> {
            latestPingTimestamp = System.currentTimeMillis();
        });
        intercept(HMessage.Direction.TOCLIENT, "LatencyPingResponse", hMessage -> {
            if (latestPingTimestamp != -1) {
                int newPing = (int) (System.currentTimeMillis() - latestPingTimestamp) / 2;
                pingVariation = pingVariation * 0.66 + (Math.abs(ping - newPing)) * 0.34;
                if (pingVariation > 10) {
                    pingVariation = 10;
                }
                ping = newPing;
            }
        });

        intercept(HMessage.Direction.TOCLIENT, "UserUpdate", hMessage -> {
            if (Arrays.stream(HEntityUpdate.parse(hMessage.getPacket()))
                    .anyMatch(hEntityUpdate -> hEntityUpdate.getAction() == HAction.Move) &&
                    System.currentTimeMillis() > latestRoomTick + 400) {
                latestRoomTick = System.currentTimeMillis() - ping;
            }
        });
        intercept(HMessage.Direction.TOSERVER, "MoveAvatar", this::onUserWalk);

        intercept(HMessage.Direction.TOCLIENT, "RoomReady", hMessage -> reset());

        intercept(HMessage.Direction.TOSERVER, "UseFurniture", hMessage -> {
            if (awaitFridge) {
                hMessage.setBlocked(true);
                fridge = hMessage.getPacket().readInteger();
                awaitFridge = false;
                rechargerEnabled = true;

                Platform.runLater(() -> {
                    stateLbl.setText("Enabled");
                    statePane.setBackground(new Background(new BackgroundFill(Paint.valueOf("#9AFD9F"), CornerRadii.EMPTY, Insets.EMPTY)));
                });

//                chatConsole.writeOutput("Enabled click recharger", false);
            }
        });

        new Thread(() -> {
            while(true) {
                Functions.sleep(1550);
                if (rechargerEnabled) {
                    sendToServer(new HPacket("UseFurniture", HMessage.Direction.TOSERVER, fridge, 0));
                }
            }
        }).start();

    }

    public void initialize() {
        reset();
    }


    private volatile boolean isClicking = false;
    private void onUserWalk(HMessage hMessage) {
        if (!clickthroughEnabled) {
            return;
        }

        if (isClicking) {
            hMessage.setBlocked(true);
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= latestWalkTimestamp + 512) {
            latestWalkTimestamp = System.currentTimeMillis();

        }
        // if click is expected to be able to happen in current room tick
        else if (latestWalkTimestamp + 512 < -getTimeSinceTick() + 500 + now - ping - pingVariation - 10) {
            int awaitTime = (int) (latestWalkTimestamp + 512 - now);
            hMessage.setBlocked(true);
            isClicking = true;
            new Thread(() -> {
                Functions.sleep(awaitTime);
                sendToServer(hMessage.getPacket());
                latestWalkTimestamp = System.currentTimeMillis();
                isClicking = false;
            }).start();
        }
    }
    private int getTimeSinceTick() {
        if (latestRoomTick == -1) {
            return 0;
        }

        long now = System.currentTimeMillis();
        return (int) ((now - latestRoomTick) + 500) % 500;
    }

    private void resetGClick() {
        rechargerEnabled = false;
        awaitFridge = false;
        fridge = -1;

        Platform.runLater(() -> {
            stateLbl.setText("Inactive");
            enableBtn.setText("Enable");
            statePane.setBackground(new Background(new BackgroundFill(Paint.valueOf("#F9B2B0"), CornerRadii.EMPTY, Insets.EMPTY)));
        });
    }

    private void reset() {
        resetGClick();

        clickthroughEnabled = false;
        Platform.runLater(() -> chkClickthrough.setSelected(false));
    }

    public void clickthroughClick(ActionEvent actionEvent) {
        clickthroughEnabled = chkClickthrough.isSelected();
        sendToClient(new HPacket("YouArePlayingGame", HMessage.Direction.TOCLIENT, clickthroughEnabled));

    }

    public void alwaysOnTopClick(ActionEvent actionEvent) {
        primaryStage.setAlwaysOnTop(chkAlwaysOnTop.isSelected());
    }

    private void enable() {
        awaitFridge = true;
        Platform.runLater(() -> {
            stateLbl.setText("Awaiting furni click");
            enableBtn.setText("Abort");
            statePane.setBackground(new Background(new BackgroundFill(Paint.valueOf("#FFC485"), CornerRadii.EMPTY, Insets.EMPTY)));
        });
    }

    private void disable() {
        resetGClick();
    }

    public void enableClick(ActionEvent actionEvent) {
        if (enableBtn.getText().equals("Enable")) {
            enable();
        }
        else {
            disable();
        }
    }
}
