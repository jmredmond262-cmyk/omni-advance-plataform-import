package com.example.data

import com.example.model.ContactCategory
import com.example.model.DailyTask
import com.example.model.NeedItem
import com.example.model.NeighborhoodContact
import com.example.model.ProductCategory
import com.example.model.TaskCategory
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * User-owned tasks, shopping items, custom contacts and contact favorites.
 * Public/sample directory contacts remain local catalog data; only favorites are synced.
 */
class PersonalDataRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun user(uid: String) = db.collection("users").document(uid)

    fun observeTasks(uid: String, callback: (Result<List<DailyTask>>) -> Unit): ListenerRegistration =
        user(uid).collection("dailyTasks").addSnapshotListener { snapshot, error ->
            if (error != null) {
                callback(Result.failure(error))
                return@addSnapshotListener
            }
            val items = snapshot?.documents.orEmpty().mapNotNull { doc ->
                val category = runCatching {
                    TaskCategory.valueOf(doc.getString("category").orEmpty())
                }.getOrNull() ?: return@mapNotNull null
                DailyTask(
                    id = doc.id,
                    title = doc.getString("title").orEmpty(),
                    category = category,
                    isCompleted = doc.getBoolean("isCompleted") ?: false,
                    timeTag = doc.getString("timeTag") ?: "09:00"
                )
            }.sortedWith(compareBy<DailyTask> { it.timeTag }.thenBy { it.title })
            callback(Result.success(items))
        }

    fun observeShoppingItems(uid: String, callback: (Result<List<NeedItem>>) -> Unit): ListenerRegistration =
        user(uid).collection("shoppingListItems").addSnapshotListener { snapshot, error ->
            if (error != null) {
                callback(Result.failure(error))
                return@addSnapshotListener
            }
            val items = snapshot?.documents.orEmpty().mapNotNull { doc ->
                val category = runCatching {
                    ProductCategory.valueOf(doc.getString("category").orEmpty())
                }.getOrNull() ?: return@mapNotNull null
                NeedItem(
                    id = doc.id,
                    name = doc.getString("name").orEmpty(),
                    category = category,
                    quantity = (doc.getLong("quantity") ?: 1L).toInt(),
                    unit = doc.getString("unit") ?: "unid.",
                    isChecked = doc.getBoolean("isChecked") ?: false
                )
            }
            callback(Result.success(items))
        }

    fun observeCustomContacts(uid: String, callback: (Result<List<NeighborhoodContact>>) -> Unit): ListenerRegistration =
        user(uid).collection("customContacts").addSnapshotListener { snapshot, error ->
            if (error != null) {
                callback(Result.failure(error))
                return@addSnapshotListener
            }
            val contacts = snapshot?.documents.orEmpty().mapNotNull { doc ->
                val category = runCatching {
                    ContactCategory.valueOf(doc.getString("category").orEmpty())
                }.getOrNull() ?: return@mapNotNull null
                NeighborhoodContact(
                    id = doc.id,
                    name = doc.getString("name").orEmpty(),
                    category = category,
                    phoneNumber = doc.getString("phoneNumber").orEmpty(),
                    statusText = doc.getString("statusText") ?: "Contacto personal",
                    rating = ((doc.get("rating") as? Number)?.toFloat() ?: 5.0f).coerceIn(0f, 5f),
                    address = doc.getString("address"),
                    isFavorite = doc.getBoolean("isFavorite") ?: false,
                    averageWaitMinutes = (doc.getLong("averageWaitMinutes"))?.toInt(),
                    isCustom = true
                )
            }
            callback(Result.success(contacts))
        }

    fun observeContactFavorites(uid: String, callback: (Result<Set<String>?>) -> Unit): ListenerRegistration =
        user(uid).collection("preferences").document("contactFavorites")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    callback(Result.failure(error))
                    return@addSnapshotListener
                }
                if (snapshot?.exists() != true) {
                    callback(Result.success(null))
                } else {
                    val ids = snapshot.get("favoriteIds") as? List<*>
                    callback(Result.success(ids.orEmpty().filterIsInstance<String>().toSet()))
                }
            }

    fun saveTask(uid: String, task: DailyTask, callback: (Result<Unit>) -> Unit = {}) {
        user(uid).collection("dailyTasks").document(task.id).set(
            mapOf(
                "id" to task.id,
                "ownerUid" to uid,
                "title" to task.title,
                "category" to task.category.name,
                "isCompleted" to task.isCompleted,
                "timeTag" to task.timeTag,
                "updatedAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge()
        ).addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun deleteTask(uid: String, taskId: String, callback: (Result<Unit>) -> Unit = {}) {
        user(uid).collection("dailyTasks").document(taskId).delete()
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun saveShoppingItem(uid: String, item: NeedItem, callback: (Result<Unit>) -> Unit = {}) {
        user(uid).collection("shoppingListItems").document(item.id).set(
            mapOf(
                "id" to item.id,
                "ownerUid" to uid,
                "name" to item.name,
                "category" to item.category.name,
                "quantity" to item.quantity,
                "unit" to item.unit,
                "isChecked" to item.isChecked,
                "updatedAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge()
        ).addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun replaceShoppingItems(uid: String, items: List<NeedItem>, callback: (Result<Unit>) -> Unit = {}) {
        val collection = user(uid).collection("shoppingListItems")
        collection.get().addOnSuccessListener { snapshot ->
            val nextIds = items.map { it.id }.toSet()
            val staleDocuments = snapshot.documents.filter { it.id !in nextIds }
            if (staleDocuments.size + items.size > 450) {
                callback(Result.failure(IllegalStateException("La lista supera el límite seguro de elementos para una sola actualización.")))
                return@addOnSuccessListener
            }
            if (staleDocuments.isEmpty() && items.isEmpty()) {
                callback(Result.success(Unit))
                return@addOnSuccessListener
            }
            val batch = db.batch()
            staleDocuments.forEach { batch.delete(it.reference) }
            items.forEach { item ->
                batch.set(collection.document(item.id), mapOf(
                    "id" to item.id,
                    "ownerUid" to uid,
                    "name" to item.name,
                    "category" to item.category.name,
                    "quantity" to item.quantity,
                    "unit" to item.unit,
                    "isChecked" to item.isChecked,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
            }
            batch.commit().addOnSuccessListener { callback(Result.success(Unit)) }
                .addOnFailureListener { callback(Result.failure(it)) }
        }.addOnFailureListener { callback(Result.failure(it)) }
    }

    fun deleteShoppingItem(uid: String, itemId: String, callback: (Result<Unit>) -> Unit = {}) {
        user(uid).collection("shoppingListItems").document(itemId).delete()
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun saveCustomContact(uid: String, contact: NeighborhoodContact, callback: (Result<Unit>) -> Unit = {}) {
        user(uid).collection("customContacts").document(contact.id).set(
            mapOf(
                "id" to contact.id,
                "ownerUid" to uid,
                "name" to contact.name,
                "category" to contact.category.name,
                "phoneNumber" to contact.phoneNumber,
                "statusText" to contact.statusText,
                "rating" to contact.rating.toDouble(),
                "address" to contact.address,
                "isFavorite" to contact.isFavorite,
                "averageWaitMinutes" to contact.averageWaitMinutes,
                "updatedAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge()
        ).addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun saveContactFavorites(uid: String, favoriteIds: Set<String>, callback: (Result<Unit>) -> Unit = {}) {
        val ref = user(uid).collection("preferences").document("contactFavorites")
        ref.set(mapOf(
            "ownerUid" to uid,
            "favoriteIds" to favoriteIds.toList(),
            "updatedAt" to FieldValue.serverTimestamp()
        ), SetOptions.merge()).addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun setContactFavorite(uid: String, contactId: String, favorite: Boolean, callback: (Result<Unit>) -> Unit = {}) {
        val ref = user(uid).collection("preferences").document("contactFavorites")
        db.runTransaction { tx ->
            val snapshot = tx.get(ref)
            val ids = (snapshot.get("favoriteIds") as? List<*>)?.filterIsInstance<String>()?.toMutableSet()
                ?: mutableSetOf()
            if (favorite) ids.add(contactId) else ids.remove(contactId)
            tx.set(ref, mapOf(
                "ownerUid" to uid,
                "favoriteIds" to ids.toList(),
                "updatedAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge())
            Unit
        }.addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }
}
