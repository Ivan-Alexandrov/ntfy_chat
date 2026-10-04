SHELL := /bin/bash
.DEFAULT_GOAL := help

# --- Android SDK -------------------------------------------------------------
# Приоритет: SDK_DIR из командной строки/env -> автоопределение -> дефолт.
SDK_CANDIDATES := \
	$(HOME)/Android/Sdk \
	$(HOME)/Android/sdk \
	/usr/lib/android-sdk \
	/opt/android-sdk \
	$(wildcard /mnt/c/Users/*/AppData/Local/Android/Sdk)

SDK_DIR ?= $(firstword $(foreach d,$(SDK_CANDIDATES),$(if $(wildcard $(d)/platform-tools),$(d))))
ANDROID_HOME ?= $(SDK_DIR)
export ANDROID_HOME

# --- JDK ---------------------------------------------------------------------
# Если JAVA_HOME не задан — ищем JBR Android Studio или системный JDK 17.
ifeq ($(JAVA_HOME),)
  JAVA_CANDIDATES := \
	$(HOME)/android-studio/jbr \
	/usr/lib/jvm/java-17-openjdk-amd64 \
	/usr/lib/jvm/java-17-openjdk
  AUTO_JAVA := $(firstword $(foreach d,$(JAVA_CANDIDATES),$(if $(wildcard $(d)/bin/java),$(d))))
  ifneq ($(AUTO_JAVA),)
    export JAVA_HOME := $(AUTO_JAVA)
  endif
endif

ADB := $(SDK_DIR)/platform-tools/adb
GRADLE := ./gradlew
APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE := app/build/outputs/apk/release/app-release-unsigned.apk

# --- Служебное ---------------------------------------------------------------
.PHONY: check-sdk
check-sdk: ## Проверить путь к Android SDK
	@if [ -z "$(SDK_DIR)" ] || [ ! -d "$(SDK_DIR)/platform-tools" ]; then \
		echo "!! Android SDK не найден: '$(SDK_DIR)'"; \
		echo "   Укажите путь: make debug SDK_DIR=/path/to/Android/Sdk"; \
		echo "   Найденные кандидаты:"; \
		for d in $(SDK_CANDIDATES); do [ -d "$$d/platform-tools" ] && echo "     - $$d"; done; \
		exit 1; \
	fi
	@if [ -z "$(JAVA_HOME)" ]; then \
		echo "!! JDK не найден. Установите: sudo apt install openjdk-17-jdk"; \
		echo "   или задайте JAVA_HOME."; \
		exit 1; \
	fi

.PHONY: local.properties
local.properties: check-sdk
	@printf 'sdk.dir=%s\n' "$(SDK_DIR)" > local.properties
	@echo ">> local.properties: sdk.dir=$(SDK_DIR)"

.PHONY: help
help: ## Показать список команд
	@echo "Ntfy Chat — сборка APK"
	@echo
	@grep -E '^[a-zA-Z_.-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "SDK_DIR   = $(SDK_DIR)"
	@echo "JAVA_HOME = $(JAVA_HOME)"

.PHONY: setup
setup: local.properties ## Показать пути и версии
	@echo ">> java: $(JAVA_HOME)" && java -version 2>&1 | head -1
	@echo ">> adb:  $(ADB)"
	@$(ADB) version 2>/dev/null | head -1 || echo "!! adb не найден"

# --- Сборка ------------------------------------------------------------------
.PHONY: debug apk
debug: local.properties ## Собрать debug APK
	$(GRADLE) assembleDebug
	@echo ">> $(APK_DEBUG)"

apk: debug ## Псевдоним для debug

.PHONY: release
release: local.properties ## Собрать release APK (unsigned)
	$(GRADLE) assembleRelease
	@echo ">> $(APK_RELEASE)"

.PHONY: clean
clean: ## Очистить артефакты сборки
	$(GRADLE) clean

.PHONY: lint
lint: local.properties ## Запустить lint
	$(GRADLE) lintDebug

# --- Устройство --------------------------------------------------------------
.PHONY: devices
devices: ## Список подключённых устройств
	$(ADB) devices -l

.PHONY: install
install: debug ## Собрать и установить debug APK
	$(ADB) install -r $(APK_DEBUG)

.PHONY: uninstall
uninstall: ## Удалить приложение с устройства
	$(ADB) uninstall ru.amigo.chat

.PHONY: run
run: install ## Собрать и установить debug APK
	@echo ">> Готово. Запусти приложение на телефоне."
