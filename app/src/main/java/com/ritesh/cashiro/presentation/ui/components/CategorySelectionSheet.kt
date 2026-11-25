package com.ritesh.cashiro.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritesh.cashiro.R
import com.ritesh.cashiro.data.database.entity.CategoryEntity
import com.ritesh.cashiro.data.database.entity.SubcategoryEntity
import com.ritesh.cashiro.presentation.ui.icons.CloseCircle
import com.ritesh.cashiro.presentation.ui.icons.Iconax
import com.ritesh.cashiro.presentation.ui.theme.Dimensions
import com.ritesh.cashiro.presentation.ui.theme.Spacing

@Composable
fun CategorySelectionSheet(
    categories: List<CategoryEntity>,
    subcategoriesMap: Map<Long, List<SubcategoryEntity>>,
    onSelectionComplete: (CategoryEntity, SubcategoryEntity?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    val query = searchQuery.text.trim()
    val resources = LocalResources.current
    val filteredCategories = remember(categories, subcategoriesMap, resources, query) {
        categories.mapNotNull { category ->
            val subcategories = subcategoriesMap[category.id].orEmpty()
            val categoryMatches = query.isEmpty() || category.name.contains(query, ignoreCase = true) ||
                category.displayLabel(resources).contains(query, ignoreCase = true)
            val matches = if (categoryMatches) subcategories else subcategories.filter {
                it.name.contains(query, ignoreCase = true) || it.displayLabel(resources).contains(query, ignoreCase = true)
            }
            if (categoryMatches || matches.isNotEmpty()) category to matches else null
        }
    }
    val expandedStates = remember(query) { mutableStateMapOf<Long, Boolean>() }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Search Bar
        Box(modifier = Modifier.padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm)) {
             SearchBarBox(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                label = {
                    Text(
                        text = stringResource(R.string.search_transaction_categories),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {},
                trailingIcon = if (searchQuery.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = TextFieldValue("") }) {
                            Icon(Iconax.CloseCircle, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                } else { {} }
            )
        }

        if (filteredCategories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.transaction_categories_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(
                        start = Dimensions.Padding.content,
                        end = Dimensions.Padding.content,
                        top = Spacing.sm,
                        bottom = 0.dp
                    )
                    .clip(RoundedCornerShape(16.dp)),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(
                    items = filteredCategories,
                    key = { it.first.id }
                ) { (category, subcategories) ->
                    val isExpanded = expandedStates[category.id] ?: query.isNotEmpty()
                    CategoryItem(
                        category = category,
                        subcategories = if (isExpanded) subcategories else emptyList(),
                        onClick = {
                            if (subcategories.isEmpty() || isExpanded) {
                                onSelectionComplete(category, null)
                            } else {
                                expandedStates[category.id] = true
                            }
                        },
                        onAddSubcategory = {},
                        onEditSubcategory = { subcategory -> onSelectionComplete(category, subcategory) },
                        showAddSubcategoryButton = false
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(Spacing.xxl))
                }
            }
        }
    }
}
