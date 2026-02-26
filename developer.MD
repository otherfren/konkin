# Developer Quickstart

`mvn clean install`

`java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar target/konkin-server-0.1.0-SNAPSHOT.jar config.toml`
