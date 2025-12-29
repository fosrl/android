val pkg: String = "net.pangolin.Pangolin.PacketTunnel"

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.pangolin.Pangolin.PacketTunnel"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
    }

    externalNativeBuild {
        cmake {
            path("tools/CMakeLists.txt")
        }
    }

    buildTypes {
        all {
           externalNativeBuild {
                cmake {
                    targets("libpangolin-go.so")
                    arguments("-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}")
                    arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
