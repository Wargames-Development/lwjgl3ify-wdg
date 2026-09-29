package me.eigenraven.lwjgl3ify.mixins.late.voicechat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gliby's voice client already closes its resources before this obsolete forced stop. */
@Pseudo
@Mixin(targets = "net.gliby.voicechat.client.networking.ClientNetwork", remap = false)
public class GlibysVoiceClientStop {

    @Redirect(
        method = "stopClientNetwork()V",
        at = @At(value = "INVOKE", target = "Ljava/lang/Thread;stop()V"),
        remap = false,
        require = 0)
    private void lwjgl3ify$interruptStoppedVoiceThread(Thread voiceThread) {
        voiceThread.interrupt();
    }
}
