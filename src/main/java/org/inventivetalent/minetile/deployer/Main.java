package org.inventivetalent.minetile.deployer;

import picocli.CommandLine;

public class Main {

	public static void main(String[] args) {
		Deployer deployer = new Deployer();
		CommandLine commandLine = new CommandLine(deployer);
		try {
			commandLine.parse(args);
			if (commandLine.isUsageHelpRequested()) {
				commandLine.usage(System.out);
				return;
			} else if (commandLine.isVersionHelpRequested()) {
				commandLine.printVersionHelp(System.out);
				return;
			}
			Boolean result = deployer.call();
			if (!result) {
				commandLine.usage(System.err);
				System.exit(-1);
			}
		} catch (CommandLine.ParameterException ex) {
			System.err.println(ex.getMessage());
			if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, System.err)) {
				ex.getCommandLine().usage(System.err);
			}
		} catch (Exception ex) {
			throw new CommandLine.ExecutionException(commandLine, "Error while calling " + deployer, ex);
		}
	}

}
