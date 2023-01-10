# Guacamole Docker Manager

A Guacamole Extention, which listens for authentications and provides docker containers on demand.

## Getting Started

```sh
cd guacamole-docker-manager
mvn clean compile assembly:single
```

```yml
---
- name: Deploy Docker extension
  hosts: guacamole
  become: true
  gather_facts: false
  tasks:
    - name: upload jar
      ansible.builtin.copy:
        src: ./guacamole_ext_docker-manager/guacamole-docker-manager/target/guacamole-docker-manager-1.0-SNAPSHOT-jar-with-dependencies-and-exclude-classes.jar
        dest: /etc/guacamole/extensions/guacamole-docker-manager.jar

    - name: Restart Tomcat
      ansible.builtin.service:
        name: tomcat9
        state: restarted
```

## Requirements

- Maven `3.8.6`
- Jave `1.8`
