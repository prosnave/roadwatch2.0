# Hilt Proguard Rules
-keep class dagger.hilt.internal.aggregatedroot.InitializableModule
-keep class dagger.hilt.android.internal.managers.ActivityComponentManager$ActivityComponentBuilderEntryPoint
-keep class dagger.hilt.android.internal.managers.ActivityComponentManager
-keep class dagger.hilt.android.internal.managers.BroadcastReceiverComponentManager
-keep class dagger.hilt.android.internal.managers.FragmentComponentManager
-keep class dagger.hilt.android.internal.managers.ServiceComponentManager
-keep class dagger.hilt.android.internal.managers.ViewComponentManager
-keep class dagger.hilt.android.internal.managers.ViewWithFragmentComponentManager
-keep class dagger.hilt.android.internal.modules.ApplicationContextModule
-keep class dagger.hilt.android.internal.modules.HiltWrapper_FragmentModule
-keep class dagger.hilt.android.internal.modules.HiltWrapper_ViewModule
-keep class dagger.hilt.android.internal.testing.root.DefaultHiltTestApplication_HiltComponents
-keep class dagger.hilt.android.testing.HiltTestApplication
-keep class dagger.hilt.processor.internal.root.RootProcessor
-keep class javax.inject.Inject
-keep class javax.inject.Singleton
-keep @javax.inject.Scope @interface *
-keep @dagger.hilt.InstallIn @interface *
-keep @dagger.hilt.components.SingletonComponent @interface *
-keep @dagger.Module @interface *
-keep @dagger.Provides @interface *
-keep @dagger.Binds @interface *
-keep @dagger.BindsInstance @interface *
-keep @dagger.BindsOptionalOf @interface *
-keep @dagger.multibindings.IntoSet @interface *
-keep @dagger.multibindings.IntoMap @interface *
-keep @dagger.multibindings.StringKey @interface *
-keep @dagger.multibindings.ClassKey @interface *
-keep @dagger.assisted.Assisted @interface *
-keep @dagger.assisted.AssistedFactory @interface *
-keep @dagger.assisted.AssistedInject @interface *
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <fields>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}
