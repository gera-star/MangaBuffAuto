    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            icon = {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null
                )
            },
            title = { Text("❤️ Поддержать проект") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Добровольное пожертвование на поддержку и развитие проекта MangaBuff Auto."
                    )
                    Text("Получатель: $SUPPORT_RECIPIENT")

                    Text(
                        "Пожертвование не является оплатой приложения, лицензии, доступа к функциям или услугам и не является обязательным."
                    )

                    Text(
                        "Вы перейдете в свой браузер.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Перевод выполняется через СБП. Сумму вы выбираете самостоятельно в приложении своего банка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Text(
                        "Мы Любим Мангу ❤️",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(SUPPORT_PAYMENT_URL)
                                )
                            )
                            showSupportDialog = false
                        } catch (_: Exception) {
                            Toast.makeText(
                                context,
                                "Не удалось открыть страницу СБП",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть страницу СБП")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSupportDialog = false }) {
                    Text("Закрыть")
                }
            }