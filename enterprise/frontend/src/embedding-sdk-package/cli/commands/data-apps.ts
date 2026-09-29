import type { Command } from "commander";

import { syncResourcesAction } from "../actions/sync-resources";

export function addDataAppsCommands(program: Command) {
  const dataAppsCommand = program
    .command("data-apps")
    .description("manage Metabase data apps");

  dataAppsCommand
    .command("sync-resources")
    .description(
      "render data app query and action definitions into its resources/ directory",
    )
    .option("--app-root <path>", "data app directory", process.cwd())
    .action(({ appRoot }: { appRoot: string }) => syncResourcesAction(appRoot));
}
