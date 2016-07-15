plugin-statistics
=================

A small still-in-testing plugin statistics library. Reports to https://dabo.guru.

Data is reported to https://dabo.guru, and is reported once every hour, starting one hour from server startup (servers running for under one hour will not have any data reported).

Plugin statistics reports a server UUID, the plugin name, the plugin version, the number of players online on the server, and the version of the server.


Data Reporting
--------------

Example data (sent to `https://dabo.guru/statistics/v1/<plugin_name>/post`):

```json
{
    "instance_uuid": "1aa76cf6-496d-11e6-8243-5c260a7a2dbe",
    "plugin_version": "2.1.5",
    "server_version": "1.8.8",
    "online_players": "21"
}
```

Note that the server UUID is generated uniquely on each server startup - it is unique to your server, but is in no way identifying. The only reason a UUID is included at all is to avoid duplicate data reporting, as plugin-statistics reports every hour, and reports are kept for 2-3 hours in the service.

The server stores each submitted data set (instance_uuid, plugin_version, server_version and online_players) for up to 3 hours. When the plugin send another report with the same UUID, the server erases all previous data for that UUID.

Every hour, on the hour, dabo.guru erases all data sets which are over 2 hours old, and then creates a "record" which aggregates the remaining data. The hourly record does not store any UUIDs, but stores the following data sets:

- Total online players for the plugin (sum of all online players on all servers)
- Total number of servers running each plugin_version (count of each server stored per plugin_version)
- Total number of plugins with each unique server_version and plugin_version combination (count of each server stored per (plugin_version, server_version))

The data in the hourly "records" is stored indefinitely. But, as you can see, it does not contain server UUIDs, or any other server-identifying information. If your server has been offline for at least 3 hours, there will be no information stored about it.

This service is similar to MCStats, but records vastly less information, and has a much more basic interface. The only reason I've created it is to view one specific trend: server versions for each plugin version.

By looking at what server versions are running the latest version of the plugin, it will be possible to see when it would be reasonable to drop support for a given Minecraft version.

Server Side
-----------

The source for the server side of plugin-statistics is located in https://github.com/daboross/dabo.guru. It's written in python, and uses the flask web framework.

Note that dabo.guru is my personal website, and the repository does contain many files unrelated to statistics collection.

Files (in dabo.guru) related to this service:
- the statistics API: https://github.com/daboross/dabo.guru/blob/master/content/statistics_api.py
- the script which deletes old data and makes hourly records: https://github.com/daboross/dabo.guru/blob/master/uwsgi_mules/record_statistics.py
