{ pkgs ? import <nixpkgs> { } }:
pkgs.mkShell {
  packages = [ pkgs.temurin-bin-17 pkgs.kotlin pkgs.gradle ];
  JAVA_HOME = "${pkgs.temurin-bin-17}";
}
