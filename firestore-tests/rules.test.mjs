import { test, before, after } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, writeBatch, arrayUnion, arrayRemove, serverTimestamp, Bytes } from 'firebase/firestore';

let env;

before(async () => {
  env = await initializeTestEnvironment({ projectId: 'kanban-rules', firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') } });
});
after(async () => { await env.cleanup(); });

const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const task = (uid, extra = {}) => ({ title: 'Appeler le garage', description: '', column: 'TODO', reminder: null, event: null, createdAt: 1, updatedAt: serverTimestamp(), updatedBy: uid, ...extra });

async function createBoard(uid, boardId, code) {
  const d = db(uid);
  const batch = writeBatch(d);
  batch.set(doc(d, 'boards', boardId), { joinCode: code, members: [uid], createdAt: serverTimestamp() });
  batch.set(doc(d, 'joinCodes', code), { boardId });
  await batch.commit();
}

test('creating a board with its join code succeeds', async () => {
  await assertSucceeds(createBoard('johan', 'b1', 'ABC234'));
});

test('an existing join code cannot be taken over', async () => {
  await createBoard('johan', 'b2', 'DEF567');
  const d = db('mallory');
  const batch = writeBatch(d);
  batch.set(doc(d, 'boards', 'evil'), { joinCode: 'DEF567', members: ['mallory'], createdAt: serverTimestamp() });
  batch.set(doc(d, 'joinCodes', 'DEF567'), { boardId: 'evil' });
  await assertFails(batch.commit());
});

test('a join code cannot point to a board one is not member of', async () => {
  await createBoard('johan', 'b3', 'GHJ234');
  await assertFails(setDoc(doc(db('mallory'), 'joinCodes', 'ZZZ234'), { boardId: 'b3' }));
});

test('a board cannot be created with someone else as member', async () => {
  await assertFails(setDoc(doc(db('mallory'), 'boards', 'b4'), { joinCode: 'X', members: ['mallory', 'johan'], createdAt: serverTimestamp() }));
});

test('a signed-in user can read a code they know but not list codes', async () => {
  await createBoard('johan', 'b5', 'KMN234');
  await assertSucceeds(getDoc(doc(db('partner'), 'joinCodes', 'KMN234')));
  await assertFails(getDocs(collection(db('partner'), 'joinCodes')));
  await assertFails(getDoc(doc(db(null), 'joinCodes', 'KMN234')));
});

test('a non-member cannot read the board nor its tasks', async () => {
  await createBoard('johan', 'b6', 'PQR234');
  await assertSucceeds(setDoc(doc(db('johan'), 'boards/b6/tasks/t1'), task('johan')));
  await assertFails(getDoc(doc(db('mallory'), 'boards', 'b6')));
  await assertFails(getDocs(collection(db('mallory'), 'boards/b6/tasks')));
  await assertFails(getDocs(collection(db('mallory'), 'boards')));
  await assertFails(setDoc(doc(db('mallory'), 'boards/b6/tasks/t2'), task('mallory')));
});

test('joining adds only oneself, then gives access to the tasks', async () => {
  await createBoard('johan', 'b7', 'STU234');
  const partner = db('partner');
  await assertFails(updateDoc(doc(partner, 'boards', 'b7'), { members: arrayUnion('partner', 'mallory') }));
  await assertFails(updateDoc(doc(partner, 'boards', 'b7'), { members: ['partner'] }));
  await assertFails(updateDoc(doc(partner, 'boards', 'b7'), { members: arrayUnion('partner'), joinCode: 'NEW234' }));
  await assertSucceeds(updateDoc(doc(partner, 'boards', 'b7'), { members: arrayUnion('partner') }));
  await assertSucceeds(updateDoc(doc(partner, 'boards', 'b7'), { members: arrayUnion('partner') }));
  await assertSucceeds(getDoc(doc(partner, 'boards', 'b7')));
  await assertSucceeds(setDoc(doc(partner, 'boards/b7/tasks/t1'), task('partner')));
  await assertSucceeds(getDocs(collection(partner, 'boards/b7/tasks')));
  await assertSucceeds(deleteDoc(doc(db('johan'), 'boards/b7/tasks/t1')));
});

test('a member cannot remove another member', async () => {
  await createBoard('johan', 'b8', 'VWX234');
  await updateDoc(doc(db('partner'), 'boards', 'b8'), { members: arrayUnion('partner') });
  await assertFails(updateDoc(doc(db('partner'), 'boards', 'b8'), { members: arrayRemove('johan') }));
  await assertFails(deleteDoc(doc(db('johan'), 'boards', 'b8')));
});

test('tasks must be well formed and signed by their writer', async () => {
  await createBoard('johan', 'b9', 'YZA234');
  const d = db('johan');
  const full = task('johan', {
    reminder: { startMillis: 1700000000000, leadMinutes: 15, calendarKey: 'x@group.calendar.google.com' },
    event: { calendarKey: 'x@group.calendar.google.com', placedBy: 'johan' },
  });
  await assertSucceeds(setDoc(doc(d, 'boards/b9/tasks/ok'), full));
  await assertFails(setDoc(doc(d, 'boards/b9/tasks/a'), task('partner')));
  await assertFails(setDoc(doc(d, 'boards/b9/tasks/b'), task('johan', { column: 'ARCHIVED' })));
  await assertFails(setDoc(doc(d, 'boards/b9/tasks/c'), task('johan', { extra: true })));
  await assertFails(setDoc(doc(d, 'boards/b9/tasks/d'), task('johan', { updatedAt: 5 })));
});

test('a task may carry a backlog rank and up to ten attachments', async () => {
  await createBoard('johan', 'b10', 'BCD234');
  const d = db('johan');
  const attachment = (i) => ({ id: `f${i}`, name: `photo${i}.jpg`, mimeType: 'image/jpeg', size: 1000 });
  const ten = Array.from({ length: 10 }, (_, i) => attachment(i));
  await assertSucceeds(setDoc(doc(d, 'boards/b10/tasks/ok'), task('johan', { rank: 2.5, attachments: ten })));
  await assertSucceeds(setDoc(doc(d, 'boards/b10/tasks/whole'), task('johan', { rank: 3, attachments: [] })));
  await assertFails(setDoc(doc(d, 'boards/b10/tasks/a'), task('johan', { rank: 'premier' })));
  await assertFails(setDoc(doc(d, 'boards/b10/tasks/b'), task('johan', { attachments: [...ten, attachment(10)] })));
  await assertFails(setDoc(doc(d, 'boards/b10/tasks/c'), task('johan', { attachments: 'f1' })));
});

const file = (size) => ({ taskId: 't1', data: Bytes.fromUint8Array(new Uint8Array(size)) });

test('a member stores, reads and deletes an attachment file', async () => {
  await createBoard('johan', 'b11', 'EFG234');
  await updateDoc(doc(db('partner'), 'boards', 'b11'), { members: arrayUnion('partner') });
  await assertSucceeds(setDoc(doc(db('johan'), 'boards/b11/files/f1'), file(900000)));
  await assertSucceeds(getDoc(doc(db('partner'), 'boards/b11/files/f1')));
  await assertFails(setDoc(doc(db('partner'), 'boards/b11/files/f1'), file(10)));
  await assertSucceeds(deleteDoc(doc(db('partner'), 'boards/b11/files/f1')));
});

test('a non-member can neither store, read nor delete attachment files', async () => {
  await createBoard('johan', 'b12', 'HJK234');
  await setDoc(doc(db('johan'), 'boards/b12/files/f1'), file(10));
  await assertFails(setDoc(doc(db('mallory'), 'boards/b12/files/f2'), file(10)));
  await assertFails(getDoc(doc(db('mallory'), 'boards/b12/files/f1')));
  await assertFails(deleteDoc(doc(db('mallory'), 'boards/b12/files/f1')));
});

test('an attachment file must be small enough and well formed', async () => {
  await createBoard('johan', 'b13', 'MNP234');
  const d = db('johan');
  await assertFails(setDoc(doc(d, 'boards/b13/files/big'), file(950001)));
  await assertFails(setDoc(doc(d, 'boards/b13/files/a'), { taskId: 't1', data: 'pas des octets' }));
  await assertFails(setDoc(doc(d, 'boards/b13/files/b'), { ...file(10), name: 'x' }));
  await assertFails(setDoc(doc(d, 'boards/b13/files/c'), { data: file(10).data }));
});
