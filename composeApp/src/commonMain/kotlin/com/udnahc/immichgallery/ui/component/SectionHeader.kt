package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.ui.theme.Dimens

@Composable
fun SectionHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.sectionHeaderHeight)
            .padding(horizontal = Dimens.sectionHeaderPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
