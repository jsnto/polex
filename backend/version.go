package main

import "github.com/gin-gonic/gin"

// Version is the backend version (single source of truth). Keep it in sync
// with the Android app's versionName (android/app/build.gradle.kts); the app
// fetches GET /api/version and warns when the two disagree.
const Version = "1.2"

// GET /api/version → {"version": Version}
func (s *state) version(c *gin.Context) {
	c.JSON(200, gin.H{"version": Version})
}
