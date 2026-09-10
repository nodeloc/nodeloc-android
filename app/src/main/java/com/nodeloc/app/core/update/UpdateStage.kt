package com.nodeloc.app.core.update

/**
 * What the update prompt is doing, so a second tap cannot start a second one.
 *
 * Here rather than beside the controller because the controller is per-flavour
 * and the prompt that reads this is not.
 */
enum class UpdateStage { Idle, Checking, Downloading }
