import React from 'react';
import { NavigationContainer, DefaultTheme } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Text, View, StyleSheet } from 'react-native';

import HomeScreen from './screens/HomeScreen';
import LiveLocationScreen from './screens/LiveLocationScreen';
import AIAlertsScreen from './screens/AIAlertsScreen';
import AppControlScreen from './screens/AppControlScreen';
import ContentFilterScreen from './screens/ContentFilterScreen';
import RemoteCameraScreen from './screens/RemoteCameraScreen';
import BehaviorReportScreen from './screens/BehaviorReportScreen';
import SettingsScreen from './screens/SettingsScreen';
import ContactsScreen from './screens/ContactsScreen';

const Tab   = createBottomTabNavigator();
const Stack = createNativeStackNavigator();

const DarkTheme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    primary:    '#7C3AED',
    background: '#080C18',
    card:       '#10162A',
    text:       '#F1F5F9',
    border:     '#1E2D50',
  },
};

const TAB_ICONS = {
  Home:     '🏠',
  Location: '📍',
  Alerts:   '⚠️',
  Apps:     '📱',
  More:     '⚙️',
};

function TabIcon({ name, focused }) {
  return (
    <View style={[styles.tabIcon, focused && styles.tabIconActive]}>
      <Text style={{ fontSize: 20 }}>{TAB_ICONS[name]}</Text>
    </View>
  );
}

function HomeTabs() {
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarIcon: ({ focused }) => <TabIcon name={route.name} focused={focused} />,
        tabBarActiveTintColor:   '#7C3AED',
        tabBarInactiveTintColor: '#475569',
        tabBarStyle: {
          backgroundColor: '#10162A',
          borderTopColor:  '#1E2D50',
          borderTopWidth:  1,
          height:          65,
          paddingBottom:   8,
          paddingTop:      8,
        },
        tabBarLabelStyle: { fontSize: 10, fontWeight: '700' },
      })}
    >
      <Tab.Screen name="Home"     component={HomeScreen} />
      <Tab.Screen name="Location" component={LiveLocationScreen} />
      <Tab.Screen name="Alerts"   component={AIAlertsScreen} />
      <Tab.Screen name="Apps"     component={AppControlScreen} />
      <Tab.Screen name="More"     component={MoreStack} />
    </Tab.Navigator>
  );
}

function MoreStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="SettingsMain" component={SettingsScreen} />
    </Stack.Navigator>
  );
}

export default function App() {
  return (
    <NavigationContainer theme={DarkTheme}>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        <Stack.Screen name="MainTabs" component={HomeTabs} />
        <Stack.Screen name="Camera"   component={RemoteCameraScreen} />
        <Stack.Screen name="Content"  component={ContentFilterScreen} />
        <Stack.Screen name="Reports"  component={BehaviorReportScreen} />
        <Stack.Screen name="Contacts" component={ContactsScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  tabIcon:       { width: 36, height: 36, borderRadius: 10, justifyContent: 'center', alignItems: 'center' },
  tabIconActive: { backgroundColor: '#7C3AED20' },
});
