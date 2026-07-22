package me.eigenraven.lwjgl3ify.relauncher;

public final class ProcessExitFixtureMain {

    private ProcessExitFixtureMain() {}

    public static void main(String[] args) throws Exception {
        final int exitCode = Integer.parseInt(args[0]);
        final long delayMillis = args.length > 1 ? Long.parseLong(args[1]) : 0L;
        final int lineCount = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        for (int index = 0; index < lineCount; index++) {
            System.out.println("fixture-output-" + index);
            System.err.println("fixture-error-" + index);
        }
        if (delayMillis > 0L) Thread.sleep(delayMillis);
        System.exit(exitCode);
    }
}
