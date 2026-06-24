import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, FlatList, TouchableOpacity,
  Alert, TextInput, StatusBar, Platform
} from 'react-native';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = 'https://YOUR_PROJECT.supabase.co';
const SUPABASE_ANON_KEY = 'YOUR_ANON_KEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

const COLORS = {
  bg: '#080C18', card: '#10162A', cardBorder: '#1E2D50',
  purple: '#7C3AED', green: '#10B981', red: '#EF4444',
  orange: '#F59E0B', blue: '#3B82F6',
  textPrimary: '#F1F5F9', textSec: '#94A3B8', textMuted: '#475569',
};

export default function ContactsScreen() {
  const [contacts, setContacts]   = useState([]);
  const [showAdd, setShowAdd]     = useState(false);
  const [newName, setNewName]     = useState('');
  const [newPhone, setNewPhone]   = useState('');
  const [newRelation, setNewRelation] = useState('');

  useEffect(() => { loadContacts(); }, []);

  const loadContacts = async () => {
    const { data } = await supabase.from('emergency_contacts')
      .select('*').eq('is_active', true).order('created_at');
    if (data) setContacts(data);
  };

  const addContact = async () => {
    if (!newName || !newPhone) return Alert.alert('Error', 'Name and phone required');
    await supabase.from('emergency_contacts').insert({
      family_id: 'YOUR_FAMILY_ID',
      name: newName, phone: newPhone, relationship: newRelation, is_active: true,
    });
    setNewName(''); setNewPhone(''); setNewRelation(''); setShowAdd(false);
    loadContacts();
  };

  const deleteContact = (id, name) => {
    Alert.alert('Delete Contact', `Remove ${name} from emergency contacts?`, [
      { text: 'Cancel' },
      { text: 'Delete', style: 'destructive', onPress: async () => {
        await supabase.from('emergency_contacts').delete().eq('id', id);
        loadContacts();
      }}
    ]);
  };

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        <Text style={styles.headerTitle}>🆘 Emergency Contacts</Text>
        <Text style={styles.headerSub}>All contacts receive SOS alerts + auto-SMS with location</Text>
      </View>

      <FlatList
        data={contacts}
        keyExtractor={item => item.id}
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 100 }}
        renderItem={({ item }) => (
          <View style={styles.contactCard}>
            <View style={styles.avatar}>
              <Text style={{ fontSize: 24 }}>👤</Text>
            </View>
            <View style={styles.contactInfo}>
              <Text style={styles.contactName}>{item.name}</Text>
              <Text style={styles.contactPhone}>{item.phone}</Text>
              <Text style={styles.contactRel}>{item.relationship || 'Emergency Contact'}</Text>
            </View>
            <TouchableOpacity onPress={() => deleteContact(item.id, item.name)}
              style={styles.deleteBtn}>
              <Text style={{ color: COLORS.red, fontSize: 16 }}>✕</Text>
            </TouchableOpacity>
          </View>
        )}
        ListEmptyComponent={
          <View style={{ alignItems: 'center', paddingTop: 60 }}>
            <Text style={{ fontSize: 48, marginBottom: 12 }}>🆘</Text>
            <Text style={{ color: COLORS.textMuted, textAlign: 'center' }}>
              No emergency contacts.{'\n'}Add contacts who will receive SOS alerts.
            </Text>
          </View>
        }
      />

      {/* Add contact form */}
      {showAdd && (
        <View style={styles.addForm}>
          <TextInput style={styles.input} placeholder="Name" placeholderTextColor={COLORS.textMuted}
            value={newName} onChangeText={setNewName} />
          <TextInput style={styles.input} placeholder="Phone (+91...)" placeholderTextColor={COLORS.textMuted}
            value={newPhone} onChangeText={setNewPhone} keyboardType="phone-pad" />
          <TextInput style={styles.input} placeholder="Relationship (Parent, Uncle...)" placeholderTextColor={COLORS.textMuted}
            value={newRelation} onChangeText={setNewRelation} />
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <TouchableOpacity style={[styles.formBtn, { backgroundColor: COLORS.cardBorder }]}
              onPress={() => setShowAdd(false)}>
              <Text style={{ color: COLORS.textSec }}>Cancel</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.formBtn, { backgroundColor: COLORS.green, flex: 1 }]}
              onPress={addContact}>
              <Text style={{ color: '#fff', fontWeight: '700' }}>✅ Add Contact</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {/* FAB */}
      {!showAdd && (
        <TouchableOpacity style={styles.fab} onPress={() => setShowAdd(true)}>
          <Text style={styles.fabText}>+</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container:    { flex: 1, backgroundColor: COLORS.bg },
  header:       { paddingTop: Platform.OS === 'ios' ? 56 : 16, paddingHorizontal: 20, paddingBottom: 12 },
  headerTitle:  { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800' },
  headerSub:    { color: COLORS.textSec, fontSize: 12, marginTop: 4 },
  contactCard:  { flexDirection: 'row', alignItems: 'center', backgroundColor: COLORS.card, borderRadius: 14, padding: 14, marginBottom: 8, borderWidth: 1, borderColor: COLORS.cardBorder },
  avatar:       { width: 48, height: 48, borderRadius: 24, backgroundColor: COLORS.purple + '20', justifyContent: 'center', alignItems: 'center', marginRight: 12 },
  contactInfo:  { flex: 1 },
  contactName:  { color: COLORS.textPrimary, fontSize: 15, fontWeight: '700' },
  contactPhone: { color: COLORS.blue, fontSize: 13, marginTop: 2 },
  contactRel:   { color: COLORS.textMuted, fontSize: 11, marginTop: 2 },
  deleteBtn:    { padding: 8 },
  addForm:      { position: 'absolute', bottom: 0, left: 0, right: 0, backgroundColor: COLORS.card, padding: 16, borderTopWidth: 1, borderColor: COLORS.cardBorder },
  input:        { backgroundColor: COLORS.bg, color: COLORS.textPrimary, borderRadius: 10, padding: 12, marginBottom: 8, borderWidth: 1, borderColor: COLORS.cardBorder, fontSize: 14 },
  formBtn:      { paddingVertical: 14, borderRadius: 10, alignItems: 'center' },
  fab:          { position: 'absolute', bottom: 24, right: 24, width: 56, height: 56, borderRadius: 28, backgroundColor: COLORS.purple, justifyContent: 'center', alignItems: 'center', elevation: 8 },
  fabText:      { color: '#fff', fontSize: 28, fontWeight: '300', marginTop: -2 },
});
