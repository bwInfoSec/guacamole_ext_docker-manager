# guacamole_ext_docker-manager
A Guacamole Extention, which listens for authentications and provides docker containers on demand.

# how to build
```
cd guacamole-docker-manager
```
```
mvn clean compile assembly:single && ansible-playbook ../ansible-test-guac-ext.yml -i ../../bwinfosec-projects/inventories/development/
```
## requirements

- maven
