package org.qortal.network.task;

import org.qortal.controller.Controller;
import org.qortal.utils.ExecuteProduceConsume.Task;

public class RNSBroadcastTask implements Task {
    public RNSBroadcastTask() {
    }

    @Override
    public String getName() {
        return "BroadcastTask";
    }

    @Override
    public void perform() throws InterruptedException {
        Controller.getInstance().doRNSNetworkBroadcast();
    }
}
