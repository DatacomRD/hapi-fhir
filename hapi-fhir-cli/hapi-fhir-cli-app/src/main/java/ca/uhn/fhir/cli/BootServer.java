package ca.uhn.fhir.cli;

import static org.fusesource.jansi.Ansi.ansi;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.fusesource.jansi.Ansi.Color;

public class BootServer {
	public static void main(String[] theArgs) {
		BaseCommand command = new RunServerCommand();
		Options options = command.getOptions();
		DefaultParser parser = new DefaultParser();
		CommandLine parsedOptions;

		try {
			String[] args = new String[]{"-port","8013","-f","dstu2"};
			parsedOptions = parser.parse(options, args, true);
			if (parsedOptions.getArgList().isEmpty()==false) {
				throw new ParseException("Unrecognized argument: " + parsedOptions.getArgList().get(0).toString());
			}

			// Actually execute the command
			command.run(parsedOptions);

		} catch (ParseException e) {
			System.err.println("Invalid command options for command: " + command.getCommandName());
			System.err.println("  " + ansi().fg(Color.RED).bold() + e.getMessage());
			System.err.println("" + ansi().fg(Color.WHITE).boldOff());
			System.exit(1);
		} catch (CommandFailureException e) {
			System.exit(1);
		} catch (Exception e) {
			System.exit(1);
		}

	}
}
