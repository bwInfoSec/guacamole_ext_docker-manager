with import <nixpkgs> { };

pkgs.mkShell rec {
  buildInputs = [
    jdk11
    maven
  ];
  shellHook = ''
    export JAVA_HOME=${pkgs.jdk11}
    PATH="${pkgs.jdk11}/bin:$PATH"
  '';
}