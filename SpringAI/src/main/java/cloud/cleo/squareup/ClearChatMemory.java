/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup;


import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Component;

/**
 *
 * @author sjensen
 */
@Component
public class ClearChatMemory {
    private final ChatMemoryRepository chatMemoryRepo;

    public ClearChatMemory(ChatMemoryRepository chatMemoryRepo) {
        this.chatMemoryRepo = chatMemoryRepo;
    }

    /**
     * Wipe out all stored messages for the given conversationId.
     * @param conversationId
     */
    public void clearMemory(String conversationId) {
        chatMemoryRepo.deleteByConversationId(conversationId);
    }
}
